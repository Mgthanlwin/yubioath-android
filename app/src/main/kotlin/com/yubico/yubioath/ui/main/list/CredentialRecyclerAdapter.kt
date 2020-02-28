package com.yubico.yubioath.ui.main.list

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import androidx.recyclerview.widget.RecyclerView
import com.yubico.yubikitold.application.oath.OathType
import com.yubico.yubioath.R
import com.yubico.yubioath.client.Code
import com.yubico.yubioath.client.Credential
import com.yubico.yubioath.ui.main.IconManager
import com.yubico.yubioath.ui.main.OathViewModel
import kotlinx.coroutines.*
import org.jetbrains.anko.imageBitmap
import org.jetbrains.anko.imageResource
import kotlin.math.min

class CredentialRecyclerAdapter(private val context: Context, private val actionHandler: ActionHandler, initialCreds: Map<Credential, Code?> = mapOf()) : RecyclerView.Adapter<CodeAdapterRecyclerViewHolder>() {
    companion object {
        private const val CREDENTIAL_STORAGE = "CREDENTIAL_STORAGE"
        private const val IS_PINNED = "IS_PINNED"
        private val NUMERIC = Regex("^[0-9]+$")
    }

    private val credentialStorage = context.getSharedPreferences(CREDENTIAL_STORAGE, Context.MODE_PRIVATE)

    private val iconManager = IconManager(context)
    private val inflater = LayoutInflater.from(context)
    var creds: Map<Credential, Code?> = initialCreds
        set(value) {
            field = value.toSortedMap(
                    compareBy<Credential> { !isPinned(it) }
                            .thenBy { it.key != OathViewModel.NDEF_KEY }
                            .thenBy { it.issuer?.toLowerCase() ?: it.name.toLowerCase() }
                            .thenBy { it.name.toLowerCase() }
            )
            notifyDataSetChanged()
            GlobalScope.launch(Dispatchers.Main) {
                notifyNextTimeout(value)
            }
        }

    private var notifyTimeout: Job? = null

    private fun Code?.valid(): Boolean = this != null && validUntil > System.currentTimeMillis()
    private fun Code?.canRefresh(): Boolean = this == null || validFrom + 5000 < System.currentTimeMillis()

    private fun Credential.hasTimer(): Boolean = type == OathType.TOTP && period != 30
    private fun Credential.canMask(code: Code?): Boolean = key == OathViewModel.NDEF_KEY && code?.value?.matches(OathViewModel.CODE_PATTERN) == false

    fun isPinned(credential: Credential): Boolean = credentialStorage.getBoolean("$IS_PINNED/${credential.deviceId}/${credential.key}", false)

    fun setPinned(credential: Credential, value: Boolean) {
        credentialStorage.edit().putBoolean("$IS_PINNED/${credential.deviceId}/${credential.key}", value).apply()
        creds = creds  //Force re-sort
        notifyDataSetChanged()
    }

    fun hasIcon(credential: Credential): Boolean = iconManager.hasIcon(credential)

    fun setIcon(credential: Credential, icon: Bitmap) = iconManager.setIcon(credential, icon)
    fun setIcon(credential: Credential, icon: Drawable) = iconManager.setIcon(credential, icon)

    fun removeIcon(credential: Credential) = iconManager.removeIcon(credential)

    private fun Code?.formatValue(): String = this?.value?.let {
        if (it.matches(NUMERIC)) {
            when (it.length) {
                8 -> it.slice(0..3) + " " + it.slice(4..7) //1234 5678
                7 -> it.slice(0..3) + " " + it.slice(4..6) //1234 567
                6 -> it.slice(0..2) + " " + it.slice(3..5) //123 456
                else -> it
            }
        } else it
    } ?: context.getString(R.string.press_for_code)

    private suspend fun notifyNextTimeout(credentials: Map<Credential, Code?>) {
        val now = System.currentTimeMillis()
        val nextTimeout = credentials.map { (cred, code) ->
            if (code != null) when (cred.type) {
                OathType.TOTP -> code.validUntil
                OathType.HOTP -> code.validFrom + 5000  // Redraw HOTP codes after 5 seconds as they can be re-calculated.
                null -> Long.MAX_VALUE
            } else -1
        }.filter { it > now }.min()

        nextTimeout?.let {
            notifyTimeout?.cancel()
            notifyTimeout = GlobalScope.launch(Dispatchers.Main) {
                delay(it - now)
                notifyDataSetChanged()
                notifyNextTimeout(creds)
            }
        }
    }

    fun getPosition(credential: Credential): Int = creds.keys.indexOf(credential)

    private fun getItem(position: Int): Map.Entry<Credential, Code?> = creds.entries.toList()[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemCount(): Int = creds.size

    interface ActionHandler {
        fun select(position: Int)
        fun calculate(credential: Credential)
        fun copy(code: Code)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CodeAdapterRecyclerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_credential, parent, false)
        view.apply {
            (this as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        return CodeAdapterRecyclerViewHolder(view)
    }


    override fun onBindViewHolder(holder: CodeAdapterRecyclerViewHolder, position: Int) {
        val (credential, code) = try {
            getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            return
        }

        holder.apply {
            icon.imageBitmap = iconManager.getIcon(credential)

            issuerView.run {
                visibility = if (credential.issuer != null) {
                    text = credential.name
                    View.VISIBLE
                } else View.GONE
            }

            pinIcon.visibility = if (isPinned(credential)) View.VISIBLE else View.GONE

            if (credential.issuer != null) {
                issuerView.text = credential.issuer
                issuerView.visibility = View.VISIBLE
            } else {
                issuerView.visibility = View.GONE
            }
            labelView.text = credential.name

            icon.setOnClickListener { actionHandler.select(position) }
            readButton.setOnClickListener { actionHandler.calculate(credential) }
            copyButton.setOnClickListener { code?.let { actionHandler.copy(it) } }
            readButton.visibility = if (credential.type == OathType.HOTP && code.canRefresh() || credential.touch && !code.valid()) View.VISIBLE else View.GONE
            copyButton.visibility = if (code != null) View.VISIBLE else View.GONE

            fun updateMask(visible: Boolean) {
                if (visible) {
                    showButton.imageResource = R.drawable.ic_visibility_off_24dp
                    codeView.text = code.formatValue()
                } else {
                    showButton.imageResource = R.drawable.ic_visibility_24dp
                    codeView.text = "".padEnd(min(code?.value?.length ?: 0, 20), '•')
                }
            }

            if (credential.canMask(code)) {
                showButton.visibility = View.VISIBLE
                showButton.setOnClickListener {
                    it.tag = it.tag != true
                    updateMask(it.tag == true)
                }
                updateMask(showButton.tag == true)
            } else {
                showButton.visibility = View.GONE
                updateMask(true)
            }

            codeView.isEnabled = code.valid()

            timeoutBar.apply {
                if (credential.hasTimer()) {
                    visibility = View.VISIBLE
                    if (code != null && code.valid()) {
                        if (animation == null || animation.hasEnded() || timeoutAt != code.validUntil) {
                            val now = System.currentTimeMillis()
                            timeoutAt = code.validUntil
                            startAnimation(timeoutAnimation.apply {
                                duration = code.validUntil - Math.min(now, code.validFrom)
                                startOffset = Math.min(0, code.validFrom - now)
                                setAnimationListener(object : Animation.AnimationListener {
                                    override fun onAnimationStart(animation: Animation?) = Unit
                                    override fun onAnimationRepeat(animation: Animation?) = Unit
                                    override fun onAnimationEnd(animation: Animation?) {
                                        notifyDataSetChanged()
                                    }
                                })
                            })
                        }
                    } else {
                        clearAnimation()
                        progress = 0
                        timeoutAt = 0
                    }
                } else {
                    clearAnimation()
                    visibility = View.GONE
                    timeoutAt = 0
                }
            }
        }

    }
}
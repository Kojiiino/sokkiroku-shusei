package com.kojiiino.sokkiroku

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pending: CancellableContinuation<String>? = null

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val cont = pending ?: return@registerForActivityResult
        pending = null
        val data = res.data
        if (data == null) {
            cont.resumeWithException(Exception("ドライブの利用が許可されませんでした"))
            return@registerForActivityResult
        }
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            val t = r.accessToken
            if (t != null) {
                cont.resume(t)
            } else {
                cont.resumeWithException(Exception("ドライブの利用が許可されませんでした"))
            }
        } catch (e: Exception) {
            cont.resumeWithException(Exception("ドライブの利用が許可されませんでした（${e.message}）"))
        }
    }

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.receiveAudio(uri)
    }

    /** レコーダーの「共有」で送られてきた録音を受け取る */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) vm.receiveAudio(uri)
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** ドライブ用のアクセストークン。許可済みなら画面を出さずに返る */
    private suspend fun driveToken(): String = suspendCancellableCoroutine { cont ->
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    pending = cont
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    val t = r.accessToken
                    if (t != null) {
                        cont.resume(t)
                    } else {
                        cont.resumeWithException(Exception("ドライブの鍵が取得できませんでした"))
                    }
                }
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(Exception("Googleの認証に失敗しました（${e.message}）"))
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.tokenProvider = { driveToken() }
        vm.pickAudio = { pickLauncher.launch(arrayOf("audio/*")) }
        handleShare(intent)
        setContent {
            SokkiTheme {
                App(vm)
            }
        }
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }
}

package io.github.jackwener.dsh.feature.account

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.net.URLEncoder
import java.util.Base64

/** #49 / #13: the sign-in WebView keeps the OAuth flow, recognizes the callback, and spots Google's embedded-browser refusal. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class SignInRoutingTest {
    private fun route(url: String) = SignInRouting.route(Uri.parse(url))

    @Test fun `every http(s) step of the flow stays in the WebView`() {
        listOf(
            "https://platform.deepseek.com/sign_in?redirect=x",
            // The tap on an account in Google's chooser (#49) and the consent step bound to the WebView session.
            "https://accounts.google.com/signin/oauth/v3/consent?as=S123&xsrf=t&part=AJi8hAM",
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=c&redirect_uri=https%3A%2F%2Fplatform.deepseek.com",
            "https://open.weixin.qq.com/connect/qrconnect?appid=a",
            "http://127.0.0.1:41234/oauth/callback?code=c&state=s",
            "HTTPS://Example.com/privacy",
            "about:blank", "data:text/html,x", "blob:https://platform.deepseek.com/1", "javascript:void(0)",
        ).forEach { assertEquals(it, WebNav.Load, route(it)) }
    }

    @Test fun `the completion link is the callback, directly or as an intent URL`() {
        assertEquals(WebNav.Returned, route("dsh-android://account/login?result=ok"))
        assertEquals(WebNav.Returned, route("DSH-Android://account/login?result=failed"))
        assertEquals(WebNav.Returned, route("intent://account/login?result=ok#Intent;scheme=dsh-android;package=io.github.jackwener.dsh;end"))
        assertEquals(WebNav.Returned, route("intent://account/login?result=cancelled#Intent;scheme=dsh-android;end"))
        assertEquals(WebNav.Drop, route("dsh-android://settings/x"))
        assertTrue(SignInRouting.isCallback(Uri.parse("dsh-android://account/login?result=ok")))
        assertFalse(SignInRouting.isCallback(Uri.parse("https://account/login")))
        assertFalse(SignInRouting.isCallback(null))
    }

    @Test fun `other apps' schemes leave the WebView`() {
        assertEquals(WebNav.IntentUrl, route("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;S.browser_fallback_url=https%3A%2F%2Fexample.com;end"))
        assertEquals(WebNav.Outside, route("weixin://dl/business/?t=x"))
        assertEquals(WebNav.Outside, route("mailto:support@deepseek.com"))
        assertEquals(WebNav.Outside, route("market://details?id=x"))
    }

    @Test fun `Google's embedded-browser refusal is detected from its URL`() {
        // authError is a base64 protobuf: field 1 = the error code.
        val proto = byteArrayOf(0x0a, 20) + "disallowed_useragent".toByteArray() + byteArrayOf(0x12, 3) + "abc".toByteArray()
        val std = Base64.getEncoder().encodeToString(proto)
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(proto)
        assertTrue(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/error?authError=${URLEncoder.encode(std, "UTF-8")}&client_id=c"))
        assertTrue(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/error/v2?authError=$url"))
        assertTrue(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/error?authError=x&error=disallowed_useragent"))
        assertTrue(SignInRouting.embeddedBlocked("https://accounts.google.com/v3/signin/rejected?continue=x"))
        assertTrue(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/v2/deniedsigninrejected?flowName=GlifWebSignIn"))

        val other = Base64.getEncoder().encodeToString(byteArrayOf(0x0a, 14) + "deleted_client".toByteArray())
        assertFalse(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/error?authError=${URLEncoder.encode(other, "UTF-8")}"))
        assertFalse(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/v3/consent?as=S1&xsrf=t"))
        assertFalse(SignInRouting.embeddedBlocked("https://accounts.google.com/o/oauth2/v2/auth?client_id=c"))
        assertFalse(SignInRouting.embeddedBlocked("https://platform.deepseek.com/help?q=disallowed_useragent"))
        assertFalse(SignInRouting.embeddedBlocked("https://accounts.google.com/signin/oauth/error?authError=%%%"))
        assertFalse(SignInRouting.embeddedBlocked(null))
        assertFalse(SignInRouting.embeddedBlocked(""))
    }

    @Test fun `and from the text of a Google page`() {
        assertTrue(SignInRouting.blockedPageText("\"Access blocked: …\\nError 403: disallowed_useragent\""))
        assertTrue(SignInRouting.blockedPageText("Couldn't sign you in\nThis browser or app may not be secure. Learn more"))
        assertTrue(SignInRouting.blockedPageText("无法让您登录\n此浏览器或应用可能不安全。"))
        assertFalse(SignInRouting.blockedPageText("Choose an account\nto continue to platform.deepseek.com"))
        assertFalse(SignInRouting.blockedPageText(null))
        assertTrue(SignInRouting.isGoogleAccounts("accounts.google.com"))
        assertTrue(SignInRouting.isGoogleAccounts("Accounts.Google.co.jp"))
        assertFalse(SignInRouting.isGoogleAccounts("platform.deepseek.com"))
        assertFalse(SignInRouting.isGoogleAccounts(null))
    }
}

package com.bambu.floatwidget.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.bambu.floatwidget.R

class GeetestDialog(
    context: Context,
    private val captchaId: String,
    private val onSuccess: (resultJson: String) -> Unit,
    private val onError: (errorMsg: String) -> Unit = {}
) : Dialog(context) {

    init {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setupView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupView() {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        root.addView(progressBar)

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (320 * context.resources.displayMetrics.density).toInt(),
                (420 * context.resources.displayMetrics.density).toInt(),
                Gravity.CENTER
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onSuccess(result: String) {
                    post {
                        dismiss()
                        onSuccess(result)
                    }
                }

                @JavascriptInterface
                fun onError(err: String) {
                    post {
                        dismiss()
                        onError(err)
                    }
                }

                @JavascriptInterface
                fun onClose() {
                    post {
                        dismiss()
                    }
                }

                @JavascriptInterface
                fun onReady() {
                    post {
                        progressBar.visibility = android.view.View.GONE
                    }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
        }
        root.addView(webView)
        setContentView(root)

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <style>
                    body, html {
                        margin: 0; padding: 0;
                        width: 100%; height: 100%;
                        background-color: transparent;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    #captcha {
                        width: 100%;
                        display: flex;
                        justify-content: center;
                    }
                </style>
                <script src="https://static.geetest.com/v4/gt4.js"></script>
            </head>
            <body>
                <div id="captcha"></div>
                <script>
                    initGeetest4({
                        captchaId: '$captchaId',
                        product: 'bind',
                        language: 'zho',
                        protocol: 'https://'
                    }, function (captchaObj) {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onReady();
                        }
                        captchaObj.onReady(function () {
                            captchaObj.showCaptcha();
                        }).onSuccess(function () {
                            var result = captchaObj.getValidate();
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onSuccess(JSON.stringify(result));
                            }
                        }).onError(function (err) {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onError(JSON.stringify(err));
                            }
                        }).onClose(function () {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onClose();
                            }
                        });
                    });
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://api.bambulab.cn", html, "text/html", "UTF-8", null)
    }
}

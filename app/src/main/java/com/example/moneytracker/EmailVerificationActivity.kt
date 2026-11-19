
package com.example.moneytracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

class EmailVerificationActivity : AppCompatActivity() {

    // ==========================================
    // FIREBASE
    // ==========================================

    private lateinit var auth: FirebaseAuth

    // ==========================================
    // UI COMPONENTS
    // ==========================================

    private lateinit var tvVerificationEmail: TextView
    private lateinit var tvVerificationStatus: TextView
    private lateinit var tvVerificationError: TextView

    private lateinit var btnCheckVerification: Button
    private lateinit var btnResendVerification: Button
    private lateinit var btnUseAnotherAccount: Button

    private lateinit var verificationProgress: ProgressBar

    // ==========================================
    // STATE
    // ==========================================

    private var isLoading = false
    private var navigationStarted = false

    private var resendAvailableAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val resendCooldown = 60_000L

    // ==========================================
    // RESEND COUNTDOWN
    // ==========================================

    private val cooldownRunnable = object : Runnable {

        override fun run() {

            if (isFinishing || isDestroyed) {
                return
            }

            val remaining =
                resendAvailableAt - System.currentTimeMillis()

            if (remaining <= 0L) {

                btnResendVerification.isEnabled = !isLoading

                btnResendVerification.text =
                    "Resend verification email"

                return
            }

            val seconds = (remaining + 999L) / 1000L

            btnResendVerification.text =
                "Resend available in ${seconds}s"

            btnResendVerification.isEnabled = false

            handler.postDelayed(this, 1000L)
        }
    }

    // ==========================================
    // COLORS
    // ==========================================

    private val backgroundColor = Color.rgb(
        246, 247, 251
    )

    private val primaryColor = Color.rgb(
        101, 88, 211
    )

    private val darkText = Color.rgb(
        32, 35, 52
    )

    private val mutedText = Color.rgb(
        133, 139, 158
    )

    private val borderColor = Color.rgb(
        235, 237, 243
    )

    private val successColor = Color.rgb(
        20, 155, 113
    )

    private val errorColor = Color.rgb(
        216, 93, 104
    )

    // ==========================================
    // ACTIVITY CREATED
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        auth = FirebaseAuth.getInstance()

        buildVerificationScreen()

        resendAvailableAt = savedInstanceState?.getLong(
            "resendAvailableAt",
            0L
        ) ?: 0L

        updateResendButton()

        val user = auth.currentUser

        if (user == null) {

            openLogin()

            return
        }

        updateEmailAddress(user)

        tvVerificationStatus.text =
            "Please verify your email address to continue using MoneyTracker."

        checkVerificationStatus(showPendingMessage = false)
    }

    // ==========================================
    // SAVE STATE
    // ==========================================

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putLong(
            "resendAvailableAt",
            resendAvailableAt
        )
    }

    // ==========================================
    // BUILD SCREEN
    // ==========================================

    private fun buildVerificationScreen() {

        val scrollView = ScrollView(this).apply {

            isFillViewport = true

            setBackgroundColor(backgroundColor)
        }

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER

            setPadding(
                dp(24),
                dp(24),
                dp(24),
                dp(24)
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            scrollView
        ) { view, insets ->

            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            insets
        }

        val card = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            gravity = Gravity.CENTER_HORIZONTAL

            setPadding(
                dp(24),
                dp(30),
                dp(24),
                dp(25)
            )

            background = roundedBackground(
                Color.WHITE,
                borderColor,
                22
            )

            elevation = dp(3).toFloat()

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ======================================
        // BRAND
        // ======================================

        card.addView(
            createText(
                "MONEYTRACKER",
                12f,
                primaryColor,
                Typeface.BOLD,
                Gravity.CENTER
            )
        )

        // ======================================
        // EMAIL ICON
        // ======================================

        val icon = createText(
            "✉",
            38f,
            primaryColor,
            Typeface.BOLD,
            Gravity.CENTER
        ).apply {

            background = roundedBackground(
                Color.rgb(239, 237, 255),
                Color.rgb(239, 237, 255),
                50
            )

            layoutParams = LinearLayout.LayoutParams(
                dp(90),
                dp(90)
            ).apply {

                topMargin = dp(28)
                bottomMargin = dp(24)
            }
        }

        card.addView(icon)

        // ======================================
        // TITLE
        // ======================================

        card.addView(
            createText(
                "Verify your email",
                23f,
                darkText,
                Typeface.BOLD,
                Gravity.CENTER
            )
        )

        // ======================================
        // DESCRIPTION
        // ======================================

        card.addView(
            createText(
                "One more step before you get started. " +
                        "Please verify your email address using " +
                        "the link we sent to your inbox.",
                13f,
                mutedText,
                Typeface.NORMAL,
                Gravity.CENTER
            ).apply {

                setLineSpacing(
                    dp(5).toFloat(),
                    1f
                )

                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {

                    topMargin = dp(14)
                    bottomMargin = dp(22)
                }
            }
        )

        // ======================================
        // EMAIL ADDRESS
        // ======================================

        tvVerificationEmail = createText(
            "",
            13f,
            darkText,
            Typeface.BOLD,
            Gravity.CENTER
        ).apply {

            setPadding(
                dp(12),
                dp(14),
                dp(12),
                dp(14)
            )

            background = roundedBackground(
                Color.rgb(246, 247, 251),
                borderColor,
                10
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(tvVerificationEmail)

        // ======================================
        // STATUS
        // ======================================

        tvVerificationStatus = createText(
            "",
            12f,
            mutedText,
            Typeface.NORMAL,
            Gravity.CENTER
        ).apply {

            setPadding(
                dp(4),
                dp(18),
                dp(4),
                dp(8)
            )
        }

        card.addView(tvVerificationStatus)

        // ======================================
        // ERROR MESSAGE
        // ======================================

        tvVerificationError = createText(
            "",
            11f,
            errorColor,
            Typeface.NORMAL,
            Gravity.CENTER
        ).apply {

            visibility = View.GONE

            setPadding(
                dp(5),
                dp(8),
                dp(5),
                dp(8)
            )
        }

        card.addView(tvVerificationError)

        // ======================================
        // PROGRESS
        // ======================================

        verificationProgress = ProgressBar(this).apply {

            visibility = View.GONE

            layoutParams = LinearLayout.LayoutParams(
                dp(38),
                dp(38)
            ).apply {

                gravity = Gravity.CENTER

                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }

        card.addView(verificationProgress)

        // ======================================
        // CHECK VERIFICATION
        // ======================================

        btnCheckVerification = createButton(
            "I've verified — Continue",
            primaryColor,
            Color.WHITE
        )

        card.addView(
            btnCheckVerification,
            buttonLayoutParams()
        )

        // ======================================
        // RESEND EMAIL
        // ======================================

        btnResendVerification = createButton(
            "Resend verification email",
            Color.rgb(239, 237, 255),
            primaryColor
        )

        card.addView(
            btnResendVerification,
            buttonLayoutParams()
        )

        // ======================================
        // ANOTHER ACCOUNT
        // ======================================

        btnUseAnotherAccount = createButton(
            "Use another account",
            Color.WHITE,
            darkText
        ).apply {

            background = roundedBackground(
                Color.WHITE,
                borderColor,
                10
            )
        }

        card.addView(
            btnUseAnotherAccount,
            buttonLayoutParams()
        )

        // ======================================
        // FOOTER
        // ======================================

        card.addView(
            createText(
                "Check your Spam or Junk folder if you " +
                        "cannot find our verification email.",
                10f,
                mutedText,
                Typeface.NORMAL,
                Gravity.CENTER
            ).apply {

                setPadding(
                    dp(8),
                    dp(20),
                    dp(8),
                    0
                )
            }
        )

        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scrollView.addView(root)

        setContentView(scrollView)

        setupClickListeners()
    }

    // ==========================================
    // TEXT VIEW HELPER
    // ==========================================

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        style: Int,
        gravity: Int
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = size

            setTextColor(color)

            typeface = Typeface.create(
                Typeface.DEFAULT,
                style
            )

            this.gravity = gravity
        }
    }

    // ==========================================
    // BUTTON HELPER
    // ==========================================

    private fun createButton(
        label: String,
        backgroundColor: Int,
        textColor: Int
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 12f

            isAllCaps = false

            typeface = Typeface.DEFAULT_BOLD

            setTextColor(textColor)

            background = roundedBackground(
                backgroundColor,
                backgroundColor,
                10
            )

            minHeight = dp(50)

            minimumHeight = dp(50)

            stateListAnimator = null
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {

            topMargin = dp(9)
        }
    }

    // ==========================================
    // CLICK LISTENERS
    // ==========================================

    private fun setupClickListeners() {

        btnCheckVerification.setOnClickListener {

            checkVerificationStatus(
                showPendingMessage = true
            )
        }

        btnResendVerification.setOnClickListener {

            resendVerificationEmail()
        }

        btnUseAnotherAccount.setOnClickListener {

            signOutAndReturnToLogin()
        }
    }

    // ==========================================
    // UPDATE EMAIL ADDRESS
    // ==========================================

    private fun updateEmailAddress(user: FirebaseUser) {

        tvVerificationEmail.text =
            user.email ?: "Email address unavailable"
    }

    // ==========================================
    // CHECK FIREBASE VERIFICATION
    // ==========================================

    private fun checkVerificationStatus(
        showPendingMessage: Boolean
    ) {

        if (isLoading || navigationStarted) {
            return
        }

        hideError()

        val user = auth.currentUser

        if (user == null) {

            openLogin()

            return
        }

        setLoading(true)

        tvVerificationStatus.text =
            "Checking your email verification status..."

        user.reload().addOnCompleteListener(this) { task ->

            setLoading(false)

            val refreshedUser = auth.currentUser

            if (refreshedUser == null) {

                openLogin()

                return@addOnCompleteListener
            }

            updateEmailAddress(refreshedUser)

            if (refreshedUser.isEmailVerified) {

                tvVerificationStatus.text =
                    "Your email has been verified successfully!"

                tvVerificationStatus.setTextColor(
                    successColor
                )

                openDashboard()

            } else if (!task.isSuccessful) {

                tvVerificationStatus.text =
                    "We could not refresh your verification status. Please check your internet connection."

                showError(
                    "Unable to check verification. Please try again."
                )

            } else {

                tvVerificationStatus.text =
                    if (showPendingMessage) {
                        "Your email is not verified yet. Open the verification link in your inbox, then check again."
                    } else {
                        "Waiting for email verification. Please check your inbox."
                    }

                if (showPendingMessage) {

                    showError(
                        "Email verification is still pending."
                    )
                }
            }
        }
    }

    // ==========================================
    // RESEND VERIFICATION EMAIL
    // ==========================================

    private fun resendVerificationEmail() {

        if (isLoading || navigationStarted) {
            return
        }

        if (System.currentTimeMillis() < resendAvailableAt) {

            updateResendButton()

            return
        }

        hideError()

        val user = auth.currentUser

        if (user == null) {

            openLogin()

            return
        }

        setLoading(true)

        tvVerificationStatus.text =
            "Sending verification email..."

        user.sendEmailVerification()
            .addOnCompleteListener(this) { task ->

                setLoading(false)

                if (task.isSuccessful) {

                    resendAvailableAt =
                        System.currentTimeMillis() +
                                resendCooldown

                    updateResendButton()

                    tvVerificationStatus.text =
                        "A new verification link has been sent to your email address."

                    tvVerificationStatus.setTextColor(
                        successColor
                    )

                } else {

                    showError(
                        getFriendlyError(task.exception)
                    )

                    tvVerificationStatus.text =
                        "We could not send the verification email."
                }
            }
    }

    // ==========================================
    // RESEND COUNTDOWN
    // ==========================================

    private fun updateResendButton() {

        handler.removeCallbacks(
            cooldownRunnable
        )

        if (resendAvailableAt <= System.currentTimeMillis()) {

            resendAvailableAt = 0L

            btnResendVerification.text =
                "Resend verification email"

            btnResendVerification.isEnabled =
                !isLoading

        } else {

            cooldownRunnable.run()
        }
    }

    // ==========================================
    // LOADING STATE
    // ==========================================

    private fun setLoading(loading: Boolean) {

        isLoading = loading

        verificationProgress.visibility =
            if (loading) View.VISIBLE else View.GONE

        btnCheckVerification.isEnabled = !loading

        btnUseAnotherAccount.isEnabled = !loading

        btnResendVerification.isEnabled =
            !loading &&
                    System.currentTimeMillis() >= resendAvailableAt

        if (!loading && resendAvailableAt > 0L) {

            updateResendButton()
        }
    }

    // ==========================================
    // ERROR MESSAGE
    // ==========================================

    private fun showError(message: String) {

        tvVerificationError.text = message

        tvVerificationError.visibility =
            View.VISIBLE
    }

    private fun hideError() {

        tvVerificationError.text = ""

        tvVerificationError.visibility =
            View.GONE
    }

    // ==========================================
    // FIREBASE ERROR HANDLING
    // ==========================================

    private fun getFriendlyError(
        exception: Exception?
    ): String {

        val message = exception?.message.orEmpty()

        return when {

            message.contains(
                "network",
                ignoreCase = true
            ) -> {
                "Network error. Please check your internet connection."
            }

            message.contains(
                "too many requests",
                ignoreCase = true
            ) -> {
                "Too many requests. Please wait before trying again."
            }

            message.contains(
                "too many",
                ignoreCase = true
            ) -> {
                "Too many requests. Please try again later."
            }

            else -> {
                "Unable to send verification email. Please try again."
            }
        }
    }

    // ==========================================
    // USE ANOTHER ACCOUNT
    // ==========================================

    private fun signOutAndReturnToLogin() {

        if (isLoading || navigationStarted) {
            return
        }

        auth.signOut()

        openLogin()
    }

    // ==========================================
    // NAVIGATION
    // ==========================================

    private fun openDashboard() {

        if (navigationStarted || isFinishing) {
            return
        }

        navigationStarted = true

        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {

            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)

        finish()
    }

    private fun openLogin() {

        if (navigationStarted || isFinishing) {
            return
        }

        navigationStarted = true

        val intent = Intent(
            this,
            Login::class.java
        ).apply {

            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)

        finish()
    }

    // ==========================================
    // BACKGROUND HELPER
    // ==========================================

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(fill)

            cornerRadius = dp(radius).toFloat()

            setStroke(
                dp(1),
                stroke
            )
        }
    }

    // ==========================================
    // DP CONVERSION
    // ==========================================

    private fun dp(value: Int): Int {

        return (value * resources.displayMetrics.density)
            .toInt()
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}

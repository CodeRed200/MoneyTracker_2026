
package com.example.moneytracker

import android.content.Context
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException


class ForgotPasswordActivity : AppCompatActivity() {

    // ==========================================
    // FIREBASE
    // ==========================================

    private lateinit var auth: FirebaseAuth

    // ==========================================
    // UI COMPONENTS
    // ==========================================

    private lateinit var etForgotEmail: EditText

    private lateinit var btnSendResetEmail: Button
    private lateinit var btnBackToLogin: View

    private lateinit var tvForgotError: TextView
    private lateinit var tvForgotSuccess: TextView

    private lateinit var forgotPasswordProgress: ProgressBar

    // ==========================================
    // ACTIVITY CREATED
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(R.layout.activity_forgot_password)

        // Initialize Firebase Authentication
        auth = FirebaseAuth.getInstance()

        // Initialize UI components
        initializeViews()

        // Configure edge-to-edge layout
        setupWindowInsets()

        // Configure button actions
        setupClickListeners()

    }

    // ==========================================
    // INITIALIZE VIEWS
    // ==========================================

    private fun initializeViews() {

        etForgotEmail = findViewById(R.id.etForgotEmail)

        btnSendResetEmail = findViewById(R.id.btnSendResetEmail)
        btnBackToLogin = findViewById(R.id.btnBackToLogin)

        tvForgotError = findViewById(R.id.tvForgotError)
        tvForgotSuccess = findViewById(R.id.tvForgotSuccess)

        forgotPasswordProgress = findViewById(
            R.id.forgotPasswordProgress
        )
    }

    // ==========================================
    // WINDOW INSETS
    // ==========================================

    private fun setupWindowInsets() {

        val rootView = findViewById<View>(R.id.main)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }
    }

    // ==========================================
    // CLICK LISTENERS
    // ==========================================

    private fun setupClickListeners() {

        // Send password reset email
        btnSendResetEmail.setOnClickListener {

            sendPasswordReset()
        }

        // Return to login screen
        btnBackToLogin.setOnClickListener {

            hideKeyboard()
            clearMessages()

            finish()
        }
    }

    // ==========================================
    // VALIDATE EMAIL
    // ==========================================

    private fun validateEmail(email: String): Boolean {

        if (email.isBlank()) {

            showError("Please enter your registered email address.")

            etForgotEmail.requestFocus()

            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {

            showError("Please enter a valid email address.")

            etForgotEmail.requestFocus()

            return false
        }

        return true
    }

    // ==========================================
    // SEND PASSWORD RESET EMAIL
    // ==========================================

    private fun sendPasswordReset() {

        hideKeyboard()
        clearMessages()

        val email = etForgotEmail.text.toString()
            .trim()
            .lowercase()

        // Validate email
        if (!validateEmail(email)) {
            return
        }

        setLoading(true)

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener(this) { task ->

                setLoading(false)

                if (task.isSuccessful) {

                    showSuccess(
                        "If an account is registered with this email, a password reset link will be sent. Please check your inbox and spam folder."
                    )

                    etForgotEmail.clearFocus()

                } else {

                    showError(
                        getFriendlyError(task.exception)
                    )
                }
            }
    }

    // ==========================================
    // FIREBASE ERROR HANDLING
    // ==========================================

    private fun getFriendlyError(
        exception: Exception?
    ): String {

        return when (exception) {

            is FirebaseNetworkException -> {

                "No internet connection. Please check your network and try again."
            }

            is FirebaseTooManyRequestsException -> {

                "Too many requests. Please wait a while before trying again."
            }

            is FirebaseAuthInvalidCredentialsException -> {

                "The email address is invalid. Please check it and try again."
            }

            else -> {

                "We could not process your request right now. Please try again later."
            }
        }
    }

    // ==========================================
    // LOADING STATE
    // ==========================================

    private fun setLoading(isLoading: Boolean) {

        btnSendResetEmail.isEnabled = !isLoading

        etForgotEmail.isEnabled = !isLoading

        btnBackToLogin.isEnabled = !isLoading

        if (isLoading) {

            forgotPasswordProgress.visibility = View.VISIBLE

            btnSendResetEmail.text = "Sending..."

        } else {

            forgotPasswordProgress.visibility = View.GONE

            btnSendResetEmail.text = "Send Reset Link"
        }
    }

    // ==========================================
    // ERROR MESSAGE
    // ==========================================

    private fun showError(message: String) {

        tvForgotSuccess.text = ""

        tvForgotSuccess.visibility = View.GONE

        tvForgotError.text = message

        tvForgotError.visibility = View.VISIBLE
    }

    // ==========================================
    // SUCCESS MESSAGE
    // ==========================================

    private fun showSuccess(message: String) {

        tvForgotError.text = ""

        tvForgotError.visibility = View.GONE

        tvForgotSuccess.text = message

        tvForgotSuccess.visibility = View.VISIBLE
    }

    // ==========================================
    // CLEAR MESSAGES
    // ==========================================

    private fun clearMessages() {

        tvForgotError.text = ""

        tvForgotError.visibility = View.GONE

        tvForgotSuccess.text = ""

        tvForgotSuccess.visibility = View.GONE
    }

    // ==========================================
    // HIDE KEYBOARD
    // ==========================================

    private fun hideKeyboard() {

        val inputMethodManager = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as? InputMethodManager

        inputMethodManager?.hideSoftInputFromWindow(
            currentFocus?.windowToken,
            0
        )

        currentFocus?.clearFocus()
    }
}

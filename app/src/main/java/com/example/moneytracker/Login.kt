
package com.example.moneytracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser

class Login : AppCompatActivity() {

    // ==========================================
    // FIREBASE
    // ==========================================

    private lateinit var auth: FirebaseAuth

    // ==========================================
    // UI COMPONENTS
    // ==========================================

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText

    private lateinit var btnTogglePassword: TextView
    private lateinit var btnForgotPassword: View
    private lateinit var btnCreateAccount: View

    private lateinit var btnLogin: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var tvLoginError: TextView

    // ==========================================
    // STATE
    // ==========================================

    private var isPasswordVisible = false
    private var isLoading = false
    private var navigationStarted = false

    // ==========================================
    // ACTIVITY CREATED
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(R.layout.activity_login)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()

        // Initialize views
        initializeViews()

        // Configure system bars
        setupWindowInsets()

        // Initialize interactions
        setupClickListeners()

        // Configure keyboard actions
        setupKeyboardActions()

        // Default password visibility
        setPasswordVisibility(false)

        // Check saved Firebase session
        checkExistingSession()
    }

    // ==========================================
    // INITIALIZE VIEWS
    // ==========================================

    private fun initializeViews() {

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)

        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        btnForgotPassword = findViewById(R.id.btnForgotPassword)
        btnCreateAccount = findViewById(R.id.btnCreateAccount)

        btnLogin = findViewById(R.id.btnLogin)
        loginProgress = findViewById(R.id.loginProgress)
        tvLoginError = findViewById(R.id.tvLoginError)
    }

    // ==========================================
    // EDGE-TO-EDGE WINDOW INSETS
    // ==========================================

    private fun setupWindowInsets() {

        val rootView = findViewById<View>(R.id.main)

        val initialLeft = rootView.paddingLeft
        val initialTop = rootView.paddingTop
        val initialRight = rootView.paddingRight
        val initialBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )

            insets
        }
    }

    // ==========================================
    // BUTTON LISTENERS
    // ==========================================

    private fun setupClickListeners() {

        // Login
        btnLogin.setOnClickListener {
            attemptLogin()
        }

        // Show / Hide password
        btnTogglePassword.setOnClickListener {
            togglePasswordVisibility()
        }

        // Forgot password
        btnForgotPassword.setOnClickListener {

            hideKeyboard()
            hideError()

            startActivity(
                Intent(
                    this,
                    ForgotPasswordActivity::class.java
                )
            )
        }

        // Create account
        btnCreateAccount.setOnClickListener {

            hideKeyboard()
            hideError()

            startActivity(
                Intent(
                    this,
                    SignUp::class.java
                )
            )
        }
    }

    // ==========================================
    // PASSWORD KEYBOARD ACTION
    // ==========================================

    private fun setupKeyboardActions() {

        etPassword.setOnEditorActionListener { _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_DONE) {

                attemptLogin()

                true

            } else {

                false
            }
        }
    }

    // ==========================================
    // PASSWORD VISIBILITY
    // ==========================================

    private fun togglePasswordVisibility() {

        setPasswordVisibility(!isPasswordVisible)
    }

    private fun setPasswordVisibility(showPassword: Boolean) {

        val cursorPosition = etPassword.selectionStart

        isPasswordVisible = showPassword

        if (showPassword) {

            etPassword.transformationMethod =
                HideReturnsTransformationMethod.getInstance()

            btnTogglePassword.text = "Hide"
            btnTogglePassword.contentDescription = "Hide password"

        } else {

            etPassword.transformationMethod =
                PasswordTransformationMethod.getInstance()

            btnTogglePassword.text = "Show"
            btnTogglePassword.contentDescription = "Show password"
        }

        val safePosition = cursorPosition.coerceIn(
            0,
            etPassword.text.length
        )

        etPassword.setSelection(safePosition)
    }

    // ==========================================
    // VALIDATE LOGIN INPUT
    // ==========================================

    private fun validateInputs(
        email: String,
        password: String
    ): Boolean {

        if (email.isBlank()) {

            showError("Please enter your email address.")

            etEmail.requestFocus()

            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {

            showError("Please enter a valid email address.")

            etEmail.requestFocus()

            return false
        }

        if (password.isBlank()) {

            showError("Please enter your password.")

            etPassword.requestFocus()

            return false
        }

        return true
    }

    // ==========================================
    // FIREBASE LOGIN
    // ==========================================

    private fun attemptLogin() {

        if (isLoading || navigationStarted) {
            return
        }

        hideError()
        hideKeyboard()

        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        // Validate input
        if (!validateInputs(email, password)) {
            return
        }

        setLoading(true)

        auth.signInWithEmailAndPassword(
            email,
            password
        ).addOnCompleteListener(this) { task ->

            if (task.isSuccessful) {

                val user = auth.currentUser

                if (user == null) {

                    setLoading(false)

                    showError(
                        "Login succeeded, but your account session could not be loaded. Please try again."
                    )

                    return@addOnCompleteListener
                }

                checkEmailVerification(user)

            } else {

                setLoading(false)

                showError(
                    getFriendlyAuthError(task.exception)
                )
            }
        }
    }

    // ==========================================
    // EMAIL VERIFICATION
    // ==========================================

    private fun checkEmailVerification(user: FirebaseUser) {

        user.reload().addOnCompleteListener(this) { reloadTask ->

            setLoading(false)

            val currentUser = auth.currentUser

            if (currentUser == null) {

                showError(
                    "Your login session could not be loaded. Please sign in again."
                )

                return@addOnCompleteListener
            }

            // Use the refreshed account information when
            // the Firebase reload succeeds.
            //
            // If reload fails, use the locally cached status.

            if (currentUser.isEmailVerified) {

                openDashboard()

            } else {

                openEmailVerification()
            }
        }
    }

    // ==========================================
    // CHECK EXISTING SESSION
    // ==========================================

    private fun checkExistingSession() {

        val currentUser = auth.currentUser ?: return

        setLoading(true)

        currentUser.reload().addOnCompleteListener(this) { reloadTask ->

            setLoading(false)

            val refreshedUser = auth.currentUser

            if (refreshedUser == null) {
                return@addOnCompleteListener
            }

            // Continue using Firebase's current user status.
            // This also allows an existing session to proceed
            // when a refresh cannot reach the network.

            if (refreshedUser.isEmailVerified) {

                openDashboard()

            } else {

                openEmailVerification()
            }
        }
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

            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)

        finish()
    }

    private fun openEmailVerification() {

        if (navigationStarted || isFinishing) {
            return
        }

        navigationStarted = true

        val intent = Intent(
            this,
            EmailVerificationActivity::class.java
        ).apply {

            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)

        finish()
    }

    // ==========================================
    // FIREBASE ERROR HANDLING
    // ==========================================

    private fun getFriendlyAuthError(
        exception: Exception?
    ): String {

        return when (exception) {

            is FirebaseNetworkException -> {

                "No internet connection. Please check your network and try again."
            }

            is FirebaseTooManyRequestsException -> {

                "Too many login attempts. Please wait a while before trying again."
            }

            is FirebaseAuthInvalidUserException -> {

                when (exception.errorCode) {

                    "ERROR_USER_DISABLED" -> {

                        "This account has been disabled. Please contact support."
                    }

                    else -> {

                        "No active account was found with this email address."
                    }
                }
            }

            is FirebaseAuthInvalidCredentialsException -> {

                "The email address or password is incorrect. Please check your details."
            }

            else -> {

                "Unable to sign in. Please check your internet connection and account details."
            }
        }
    }

    // ==========================================
    // LOADING STATE
    // ==========================================

    private fun setLoading(loading: Boolean) {

        isLoading = loading

        btnLogin.isEnabled = !loading

        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading

        btnTogglePassword.isEnabled = !loading
        btnForgotPassword.isEnabled = !loading
        btnCreateAccount.isEnabled = !loading

        if (loading) {

            loginProgress.visibility = View.VISIBLE

            btnLogin.text = "Signing in..."

        } else {

            loginProgress.visibility = View.GONE

            btnLogin.text = "Login"
        }
    }

    // ==========================================
    // ERROR MESSAGE
    // ==========================================

    private fun showError(message: String) {

        tvLoginError.text = message

        tvLoginError.visibility = View.VISIBLE
    }

    private fun hideError() {

        tvLoginError.text = ""

        tvLoginError.visibility = View.GONE
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

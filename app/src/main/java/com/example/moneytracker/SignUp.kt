
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
import android.widget.CheckBox
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
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class SignUp : AppCompatActivity() {

    // ==========================================
    // FIREBASE
    // ==========================================

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // ==========================================
    // INPUT FIELDS
    // ==========================================

    private lateinit var etFullName: EditText
    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText

    // ==========================================
    // BUTTONS AND CONTROLS
    // ==========================================

    private lateinit var btnTogglePassword: TextView
    private lateinit var btnToggleConfirmPassword: TextView

    private lateinit var cbTerms: CheckBox

    private lateinit var btnCreateAccount: Button
    private lateinit var btnBackToLogin: View

    // ==========================================
    // STATUS VIEWS
    // ==========================================

    private lateinit var tvSignupError: TextView
    private lateinit var tvSignupSuccess: TextView
    private lateinit var signupProgress: ProgressBar

    // ==========================================
    // PASSWORD VISIBILITY
    // ==========================================

    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    // ==========================================
    // ACTIVITY CREATED
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContentView(R.layout.activity_sign_up)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Initialize screen components
        initializeViews()

        // Handle system bars
        setupWindowInsets()

        // Setup button actions
        setupClickListeners()

        // Setup keyboard actions
        setupKeyboardActions()

        // Initial password visibility
        setPasswordVisibility(false)
        setConfirmPasswordVisibility(false)
    }

    // ==========================================
    // INITIALIZE VIEWS
    // ==========================================

    private fun initializeViews() {

        etFullName = findViewById(R.id.etFullName)
        etUsername = findViewById(R.id.etUsername)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)

        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        btnToggleConfirmPassword = findViewById(R.id.btnToggleConfirmPassword)

        cbTerms = findViewById(R.id.cbTerms)

        btnCreateAccount = findViewById(R.id.btnCreateAccount)
        btnBackToLogin = findViewById(R.id.btnBackToLogin)

        tvSignupError = findViewById(R.id.tvSignupError)
        tvSignupSuccess = findViewById(R.id.tvSignupSuccess)

        signupProgress = findViewById(R.id.signupProgress)
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

        // Create account
        btnCreateAccount.setOnClickListener {

            registerAccount()
        }

        // Show or hide password
        btnTogglePassword.setOnClickListener {

            setPasswordVisibility(!isPasswordVisible)
        }

        // Show or hide confirm password
        btnToggleConfirmPassword.setOnClickListener {

            setConfirmPasswordVisibility(!isConfirmPasswordVisible)
        }

        // Back to login
        btnBackToLogin.setOnClickListener {

            hideKeyboard()
            clearMessages()

            finish()
        }
    }

    // ==========================================
    // KEYBOARD ACTIONS
    // ==========================================

    private fun setupKeyboardActions() {

        etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_DONE) {

                registerAccount()

                true

            } else {

                false
            }
        }
    }

    // ==========================================
    // PASSWORD VISIBILITY
    // ==========================================

    private fun setPasswordVisibility(showPassword: Boolean) {

        val cursorPosition = etPassword.selectionStart

        isPasswordVisible = showPassword

        etPassword.transformationMethod = if (showPassword) {

            HideReturnsTransformationMethod.getInstance()

        } else {

            PasswordTransformationMethod.getInstance()
        }

        btnTogglePassword.text = if (showPassword) {
            "Hide"
        } else {
            "Show"
        }

        btnTogglePassword.contentDescription = if (showPassword) {
            "Hide password"
        } else {
            "Show password"
        }

        etPassword.setSelection(
            cursorPosition.coerceIn(0, etPassword.text.length)
        )
    }

    private fun setConfirmPasswordVisibility(showPassword: Boolean) {

        val cursorPosition = etConfirmPassword.selectionStart

        isConfirmPasswordVisible = showPassword

        etConfirmPassword.transformationMethod = if (showPassword) {

            HideReturnsTransformationMethod.getInstance()

        } else {

            PasswordTransformationMethod.getInstance()
        }

        btnToggleConfirmPassword.text = if (showPassword) {
            "Hide"
        } else {
            "Show"
        }

        btnToggleConfirmPassword.contentDescription = if (showPassword) {
            "Hide confirm password"
        } else {
            "Show confirm password"
        }

        etConfirmPassword.setSelection(
            cursorPosition.coerceIn(0, etConfirmPassword.text.length)
        )
    }

    // ==========================================
    // INPUT VALIDATION
    // ==========================================

    private fun validateInputs(
        fullName: String,
        username: String,
        email: String,
        password: String,
        confirmPassword: String
    ): Boolean {

        if (fullName.isBlank()) {

            showError("Please enter your full name.")
            etFullName.requestFocus()

            return false
        }

        if (fullName.length < 2) {

            showError("Your full name must contain at least 2 characters.")
            etFullName.requestFocus()

            return false
        }

        if (username.isBlank()) {

            showError("Please enter a username.")
            etUsername.requestFocus()

            return false
        }

        if (!username.matches(Regex("^[A-Za-z0-9_]{3,20}$"))) {

            showError(
                "Username must be 3–20 characters and contain only letters, numbers or underscores."
            )

            etUsername.requestFocus()

            return false
        }

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

            showError("Please create a password.")
            etPassword.requestFocus()

            return false
        }

        if (password.length < 8) {

            showError("Your password must contain at least 8 characters.")
            etPassword.requestFocus()

            return false
        }

        if (confirmPassword.isBlank()) {

            showError("Please confirm your password.")
            etConfirmPassword.requestFocus()

            return false
        }

        if (password != confirmPassword) {

            showError("Your passwords do not match.")
            etConfirmPassword.requestFocus()

            return false
        }

        if (!cbTerms.isChecked) {

            showError("Please accept the Terms and Conditions to continue.")
            cbTerms.requestFocus()

            return false
        }

        return true
    }

    // ==========================================
    // REGISTER ACCOUNT
    // ==========================================

    private fun registerAccount() {

        hideKeyboard()
        clearMessages()

        val fullName = etFullName.text.toString().trim()

        val username = etUsername.text.toString().trim()

        val email = etEmail.text.toString().trim().lowercase()

        val password = etPassword.text.toString()

        val confirmPassword = etConfirmPassword.text.toString()

        // Validate input
        if (!validateInputs(
                fullName,
                username,
                email,
                password,
                confirmPassword
            )
        ) {

            return
        }

        setLoading(true)

        // Create Firebase Authentication account
        auth.createUserWithEmailAndPassword(
            email,
            password
        ).addOnCompleteListener(this) { task ->

            if (task.isSuccessful) {

                val user = auth.currentUser

                if (user == null) {

                    setLoading(false)

                    showError(
                        "Your account was created, but its session could not be loaded. Please sign in."
                    )

                    return@addOnCompleteListener
                }

                // Continue with profile setup
                saveUserProfile(
                    userId = user.uid,
                    fullName = fullName,
                    username = username,
                    email = email
                )

            } else {

                setLoading(false)

                showError(
                    getFriendlyAuthError(task.exception)
                )
            }
        }
    }

    // ==========================================
    // SAVE FIRESTORE USER PROFILE
    // ==========================================

    private fun saveUserProfile(
        userId: String,
        fullName: String,
        username: String,
        email: String
    ) {

        // Set Firebase Authentication display name
        val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(fullName)
            .build()

        val authUser = auth.currentUser

        if (authUser == null) {

            setLoading(false)

            showError(
                "Account created, but the user session is unavailable."
            )

            return
        }

        authUser.updateProfile(profileUpdates)
            .addOnCompleteListener(this) { profileTask ->

                // Prepare user profile document.
                // Never store the password in Firestore.

                val profile = hashMapOf<String, Any>(
                    "uid" to userId,
                    "fullName" to fullName,
                    "username" to username,
                    "usernameLowercase" to username.lowercase(),
                    "email" to email,
                    "emailVerified" to false,
                    "currency" to "PHP",
                    "timezone" to "Asia/Manila",
                    "darkMode" to false,
                    "accountStatus" to "active",
                    "termsAccepted" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                firestore.collection("users")
                    .document(userId)
                    .set(profile, SetOptions.merge())
                    .addOnCompleteListener(this) { firestoreTask ->

                        val warnings = mutableListOf<String>()

                        if (!profileTask.isSuccessful) {

                            warnings.add(
                                "The account name could not be updated in Firebase Authentication."
                            )
                        }

                        if (!firestoreTask.isSuccessful) {

                            warnings.add(
                                "Your profile could not be saved to the cloud. Check your Firestore security rules and internet connection."
                            )
                        }

                        // Send the verification email even if
                        // saving the Firestore profile failed.
                        sendVerificationEmail(warnings)
                    }
            }
    }

    // ==========================================
    // SEND EMAIL VERIFICATION
    // ==========================================

    private fun sendVerificationEmail(
        previousWarnings: List<String>
    ) {

        val user = auth.currentUser

        if (user == null) {

            setLoading(false)

            showError(
                "Your account was created, but the verification session is unavailable. Please sign in."
            )

            return
        }

        user.sendEmailVerification()
            .addOnCompleteListener(this) { task ->

                setLoading(false)

                val warnings = previousWarnings.toMutableList()

                if (!task.isSuccessful) {

                    warnings.add(
                        "We could not send the verification email. You can try again from the verification screen."
                    )
                }

                // Open email verification screen.
                openEmailVerification(
                    email = user.email.orEmpty(),
                    verificationSent = task.isSuccessful,
                    warning = warnings.joinToString("\n")
                )
            }
    }

    // ==========================================
    // NAVIGATION TO EMAIL VERIFICATION
    // ==========================================

    private fun openEmailVerification(
        email: String,
        verificationSent: Boolean,
        warning: String
    ) {

        val intent = Intent(
            this,
            EmailVerificationActivity::class.java
        ).apply {

            putExtra("verification_email", email)

            putExtra("verification_sent", verificationSent)

            putExtra("signup_warning", warning)

            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)

        finish()
    }

    // ==========================================
    // FIREBASE AUTH ERROR HANDLING
    // ==========================================

    private fun getFriendlyAuthError(
        exception: Exception?
    ): String {

        return when (exception) {

            is FirebaseAuthUserCollisionException -> {

                "An account with this email already exists. Please sign in instead."
            }

            is FirebaseAuthWeakPasswordException -> {

                "Your password is too weak. Please choose a stronger password."
            }

            is FirebaseAuthInvalidCredentialsException -> {

                "The email address is invalid. Please check it and try again."
            }

            is FirebaseNetworkException -> {

                "No internet connection. Please check your network and try again."
            }

            is FirebaseTooManyRequestsException -> {

                "Too many attempts. Please wait a while before trying again."
            }

            else -> {

                "Unable to create your account. Please try again. ${exception?.localizedMessage.orEmpty()}"
            }
        }
    }

    // ==========================================
    // LOADING STATE
    // ==========================================

    private fun setLoading(isLoading: Boolean) {

        btnCreateAccount.isEnabled = !isLoading

        btnBackToLogin.isEnabled = !isLoading

        etFullName.isEnabled = !isLoading
        etUsername.isEnabled = !isLoading
        etEmail.isEnabled = !isLoading
        etPassword.isEnabled = !isLoading
        etConfirmPassword.isEnabled = !isLoading

        btnTogglePassword.isEnabled = !isLoading
        btnToggleConfirmPassword.isEnabled = !isLoading

        cbTerms.isEnabled = !isLoading

        if (isLoading) {

            signupProgress.visibility = View.VISIBLE

            btnCreateAccount.text = "Creating Account..."

        } else {

            signupProgress.visibility = View.GONE

            btnCreateAccount.text = "Create Account"
        }
    }

    // ==========================================
    // ERROR MESSAGE
    // ==========================================

    private fun showError(message: String) {

        tvSignupSuccess.visibility = View.GONE

        tvSignupSuccess.text = ""

        tvSignupError.text = message

        tvSignupError.visibility = View.VISIBLE
    }

    // ==========================================
    // SUCCESS MESSAGE
    // ==========================================

    private fun showSuccess(message: String) {

        tvSignupError.visibility = View.GONE

        tvSignupError.text = ""

        tvSignupSuccess.text = message

        tvSignupSuccess.visibility = View.VISIBLE
    }

    // ==========================================
    // CLEAR MESSAGES
    // ==========================================

    private fun clearMessages() {

        tvSignupError.text = ""

        tvSignupError.visibility = View.GONE

        tvSignupSuccess.text = ""

        tvSignupSuccess.visibility = View.GONE
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

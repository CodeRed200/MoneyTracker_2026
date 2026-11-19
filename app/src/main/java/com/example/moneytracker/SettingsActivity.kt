
package com.example.moneytracker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val SETTINGS_PREFS = "money_tracker_settings"

        // Existing local data stores used by the other app modules.
        private const val SALARY_PREFS = "money_tracker_salary"
        private const val SAVINGS_PREFS = "money_tracker_savings"
        private const val OBLIGATIONS_PREFS = "money_tracker_obligations"

        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_USERNAME = "username"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_CURRENCY = "currency"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_PAY_SCHEDULE = "pay_schedule"
        private const val KEY_PAYDAY_ONE = "payday_one"
        private const val KEY_SECOND_PAYDAY_TYPE = "second_payday_type"
        private const val KEY_PAYDAY_TWO = "payday_two"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_LAST_SYNC = "last_sync_time"
    }

    private val settingsPrefs: SharedPreferences by lazy {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
    }

    private var updatingBiometricSwitch = false

    // Export destination selected by Android's document picker.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                exportFinancialData(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applySavedTheme()
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        loadSettings()
        setupClickListeners()
        setupPreferenceListeners()
        refreshEmailVerificationStatus()
        refreshSyncStatus()
        updateAppVersion()
    }

    override fun onResume() {
        super.onResume()
        refreshEmailVerificationStatus()
        refreshSyncStatus()
    }

    // ---------------------------------------------------------
    // FIREBASE HELPERS
    // ---------------------------------------------------------

    private fun firebaseIsConfigured(): Boolean {
        return try {
            FirebaseApp.getApps(this).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun getFirebaseAuth(): FirebaseAuth? {
        return try {
            if (firebaseIsConfigured()) FirebaseAuth.getInstance() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getFirestore(): FirebaseFirestore? {
        return try {
            if (firebaseIsConfigured()) FirebaseFirestore.getInstance() else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------
    // INITIAL LOAD
    // ---------------------------------------------------------

    private fun loadSettings() {
        val user = getFirebaseAuth()?.currentUser

        val savedName = settingsPrefs.getString(KEY_FULL_NAME, null)
        val name = savedName ?: user?.displayName ?: "Your Name"

        val savedUsername = settingsPrefs.getString(KEY_USERNAME, null)
        val username = savedUsername
            ?: user?.email?.substringBefore("@")
            ?: "username"

        tv(R.id.tvSettingsName).text = name
        tv(R.id.tvSettingsUsername).text = "@${username.removePrefix("@")}"
        tv(R.id.tvSettingsEmail).text = user?.email ?: "Not signed in"

        updateProfileInitial(name)

        et(R.id.etSettingsFullName).setText(name)
        et(R.id.etSettingsUsername).setText(username.removePrefix("@"))

        selectSpinnerValue(
            R.id.spSettingsCurrency,
            settingsPrefs.getString(KEY_CURRENCY, "PHP (₱)") ?: "PHP (₱)"
        )

        selectSpinnerValue(
            R.id.spSettingsDateFormat,
            settingsPrefs.getString(KEY_DATE_FORMAT, "MMM d, yyyy") ?: "MMM d, yyyy"
        )

        selectSpinnerValue(
            R.id.spSettingsPaySchedule,
            settingsPrefs.getString(KEY_PAY_SCHEDULE, "Twice a month")
                ?: "Twice a month"
        )

        selectSpinnerValue(
            R.id.spSettingsSecondPaydayType,
            settingsPrefs.getString(KEY_SECOND_PAYDAY_TYPE, "Last day of month")
                ?: "Last day of month"
        )

        et(R.id.etSettingsPaydayOne).setText(
            settingsPrefs.getInt(KEY_PAYDAY_ONE, 15).toString()
        )

        et(R.id.etSettingsPaydayTwo).setText(
            settingsPrefs.getInt(KEY_PAYDAY_TWO, 30).toString()
        )

        updatingBiometricSwitch = true
        switch(R.id.switchDarkMode).isChecked =
            settingsPrefs.getBoolean(KEY_DARK_MODE, false)

        switch(R.id.switchBiometricLock).isChecked =
            settingsPrefs.getBoolean(KEY_BIOMETRIC, false)
        updatingBiometricSwitch = false

        updateBiometricStatus()
        updatePaydayControls()
        updatePaydayExplanation()
        updateLastSyncLabel()
    }

    private fun setupClickListeners() {
        tv(R.id.btnSettingsBack).setOnClickListener {
            finish()
        }

        tv(R.id.btnEditProfile).setOnClickListener {
            showProfileEditForm()
        }

        button(R.id.btnCancelProfileEdit).setOnClickListener {
            cancelProfileEdit()
        }

        button(R.id.btnSaveProfile).setOnClickListener {
            saveProfile()
        }

        button(R.id.btnSavePreferences).setOnClickListener {
            savePreferences()
        }

        button(R.id.btnSavePaydaySettings).setOnClickListener {
            savePaydaySettings()
        }

        tv(R.id.btnSettingsVerifyEmail).setOnClickListener {
            sendEmailVerification()
        }

        tv(R.id.btnSettingsChangePassword).setOnClickListener {
            showPasswordResetDialog()
        }

        button(R.id.btnSettingsSyncNow).setOnClickListener {
            backupLocalDataToFirebase()
        }

        tv(R.id.btnSettingsExportData).setOnClickListener {
            val fileName = "my_finance_backup_${
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            }.csv"

            exportLauncher.launch(fileName)
        }

        tv(R.id.btnSettingsClearCache).setOnClickListener {
            confirmClearCache()
        }

        tv(R.id.btnSettingsPrivacy).setOnClickListener {
            showPrivacyInformation()
        }

        tv(R.id.btnSettingsTerms).setOnClickListener {
            showTermsInformation()
        }

        button(R.id.btnSettingsLogout).setOnClickListener {
            confirmLogout()
        }

        tv(R.id.btnSettingsDeleteAccount).setOnClickListener {
            confirmDeleteAccount()
        }
    }

    // ---------------------------------------------------------
    // PROFILE
    // ---------------------------------------------------------

    private fun showProfileEditForm() {
        tv(R.id.tvSettingsProfileError).visibility = View.GONE
        et(R.id.etSettingsFullName).setText(
            settingsPrefs.getString(
                KEY_FULL_NAME,
                getFirebaseAuth()?.currentUser?.displayName ?: ""
            )
        )

        et(R.id.etSettingsUsername).setText(
            settingsPrefs.getString(
                KEY_USERNAME,
                getFirebaseAuth()?.currentUser?.email?.substringBefore("@") ?: ""
            )
        )

        findViewById<View>(R.id.profileEditForm).visibility = View.VISIBLE
    }

    private fun cancelProfileEdit() {
        findViewById<View>(R.id.profileEditForm).visibility = View.GONE
        tv(R.id.tvSettingsProfileError).visibility = View.GONE
    }

    private fun saveProfile() {
        val fullName = et(R.id.etSettingsFullName).text.toString().trim()
        val username = et(R.id.etSettingsUsername).text.toString()
            .trim()
            .removePrefix("@")

        if (fullName.isBlank()) {
            showProfileError("Please enter your full name.")
            return
        }

        if (username.isBlank()) {
            showProfileError("Please enter a username.")
            return
        }

        settingsPrefs.edit()
            .putString(KEY_FULL_NAME, fullName)
            .putString(KEY_USERNAME, username)
            .apply()

        tv(R.id.tvSettingsName).text = fullName
        tv(R.id.tvSettingsUsername).text = "@$username"
        updateProfileInitial(fullName)

        findViewById<View>(R.id.profileEditForm).visibility = View.GONE
        showSuccess("Profile saved on this device.")

        val user = getFirebaseAuth()?.currentUser
        if (user != null) {
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName(fullName)
                .build()

            user.updateProfile(profileUpdate)
                .addOnFailureListener { exception ->
                    showError(
                        "Your profile was saved locally, but Firebase could not update it: " +
                                (exception.localizedMessage ?: "Please try again.")
                    )
                }
        }
    }

    private fun showProfileError(message: String) {
        tv(R.id.tvSettingsProfileError).text = message
        tv(R.id.tvSettingsProfileError).visibility = View.VISIBLE
    }

    private fun updateProfileInitial(name: String) {
        val initial = name.trim().firstOrNull()?.toString()
            ?.uppercase(Locale.getDefault()) ?: "U"

        tv(R.id.tvSettingsProfileInitial).text = initial
    }

    // ---------------------------------------------------------
    // APP PREFERENCES
    // ---------------------------------------------------------

    private fun setupPreferenceListeners() {
        switch(R.id.switchDarkMode).setOnCheckedChangeListener { _, checked ->
            settingsPrefs.edit()
                .putBoolean(KEY_DARK_MODE, checked)
                .apply()

            AppCompatDelegate.setDefaultNightMode(
                if (checked) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }

        switch(R.id.switchBiometricLock).setOnCheckedChangeListener { _, checked ->
            if (updatingBiometricSwitch) return@setOnCheckedChangeListener

            if (checked) {
                requestBiometricEnable()
            } else {
                settingsPrefs.edit()
                    .putBoolean(KEY_BIOMETRIC, false)
                    .apply()

                updateBiometricStatus()
                showSuccess("Biometric preference disabled.")
            }
        }

        val scheduleSpinner = spinner(R.id.spSettingsPaySchedule)
        scheduleSpinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updatePaydayControls()
                updatePaydayExplanation()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val secondPaydaySpinner = spinner(R.id.spSettingsSecondPaydayType)
        secondPaydaySpinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updatePaydayControls()
                updatePaydayExplanation()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun savePreferences() {
        val currency = spinner(R.id.spSettingsCurrency).selectedItem?.toString()
            ?: "PHP (₱)"

        val dateFormat = spinner(R.id.spSettingsDateFormat).selectedItem?.toString()
            ?: "MMM d, yyyy"

        settingsPrefs.edit()
            .putString(KEY_CURRENCY, currency)
            .putString(KEY_DATE_FORMAT, dateFormat)
            .apply()

        showSuccess("App preferences saved.")
    }

    private fun applySavedTheme() {
        val darkMode = getSharedPreferences(
            SETTINGS_PREFS,
            MODE_PRIVATE
        ).getBoolean(KEY_DARK_MODE, false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    // ---------------------------------------------------------
    // PAYDAY SETTINGS
    // ---------------------------------------------------------

    private fun updatePaydayControls() {
        val schedule = spinner(R.id.spSettingsPaySchedule).selectedItem
            ?.toString() ?: "Twice a month"

        val usesTwoPaydays = schedule == "Twice a month" || schedule == "Custom"

        spinner(R.id.spSettingsSecondPaydayType).isEnabled = usesTwoPaydays

        val secondPaydayType = spinner(R.id.spSettingsSecondPaydayType)
            .selectedItem?.toString() ?: "Last day of month"

        val showCustomDay = usesTwoPaydays && secondPaydayType == "Fixed day"

        findViewById<View>(R.id.settingsSecondPaydayCustomContainer).visibility =
            if (showCustomDay) View.VISIBLE else View.GONE
    }

    private fun updatePaydayExplanation() {
        val schedule = spinner(R.id.spSettingsPaySchedule).selectedItem
            ?.toString() ?: "Twice a month"

        val firstPayday = et(R.id.etSettingsPaydayOne).text.toString().ifBlank {
            "15"
        }

        val secondPaydayType = spinner(R.id.spSettingsSecondPaydayType)
            .selectedItem?.toString() ?: "Last day of month"

        val secondPayday = et(R.id.etSettingsPaydayTwo).text.toString().ifBlank {
            "30"
        }

        val message = when (schedule) {
            "Twice a month" -> {
                val second = when (secondPaydayType) {
                    "Last day of month" -> "the last day of each month"
                    "Fixed day" -> "day $secondPayday of each month"
                    else -> "no second monthly payday"
                }

                if (firstPayday == "15" &&
                    secondPaydayType == "Last day of month"
                ) {
                    "Your 15th salary covers the 15th through month-end. " +
                            "Your month-end salary covers the 1st–14th of the next month."
                } else {
                    "Your first payday is set to day $firstPayday, and your second payday is set to $second."
                }
            }

            "Monthly" -> {
                "Your selected monthly payday is day $firstPayday. " +
                        "The Calendar and Salary modules must use this setting for their dates."
            }

            "Weekly" -> {
                "Weekly pay is selected. This saves your preference; weekly payday " +
                        "generation still needs to be connected to the Calendar and Salary modules."
            }

            "Biweekly" -> {
                "Biweekly pay is selected. This saves your preference; recurring " +
                        "payday generation still needs to be connected to the Calendar and Salary modules."
            }

            else -> {
                "Custom pay schedule selected. Your chosen payday settings are saved " +
                        "for use by the other finance modules."
            }
        }

        tv(R.id.tvSettingsPaydayExplanation).text = message
    }

    private fun savePaydaySettings() {
        val schedule = spinner(R.id.spSettingsPaySchedule).selectedItem
            ?.toString() ?: "Twice a month"

        val firstPayday = et(R.id.etSettingsPaydayOne).text.toString().trim()

        val secondPaydayType = spinner(R.id.spSettingsSecondPaydayType)
            .selectedItem?.toString() ?: "Last day of month"

        val secondPayday = et(R.id.etSettingsPaydayTwo).text.toString().trim()

        val firstDay = firstPayday.toIntOrNull()

        if (firstDay == null || firstDay !in 1..31) {
            showPaydayError("First payday must be a number from 1 to 31.")
            return
        }

        val usesTwoPaydays = schedule == "Twice a month" || schedule == "Custom"

        var secondDay = settingsPrefs.getInt(KEY_PAYDAY_TWO, 30)

        if (usesTwoPaydays && secondPaydayType == "Fixed day") {
            secondDay = secondPayday.toIntOrNull() ?: -1

            if (secondDay !in 1..31) {
                showPaydayError("Second payday must be a number from 1 to 31.")
                return
            }

            if (secondDay == firstDay) {
                showPaydayError("Your first and second payday cannot be the same day.")
                return
            }
        }

        settingsPrefs.edit()
            .putString(KEY_PAY_SCHEDULE, schedule)
            .putInt(KEY_PAYDAY_ONE, firstDay)
            .putString(KEY_SECOND_PAYDAY_TYPE, secondPaydayType)
            .putInt(KEY_PAYDAY_TWO, secondDay)
            .apply()

        tv(R.id.tvSettingsPaydayError).visibility = View.GONE
        updatePaydayExplanation()
        showSuccess("Payday settings saved.")
    }

    private fun showPaydayError(message: String) {
        tv(R.id.tvSettingsPaydayError).text = message
        tv(R.id.tvSettingsPaydayError).visibility = View.VISIBLE
    }

    // ---------------------------------------------------------
    // EMAIL VERIFICATION
    // ---------------------------------------------------------

    private fun refreshEmailVerificationStatus() {
        val user = getFirebaseAuth()?.currentUser

        if (user == null) {
            tv(R.id.tvSettingsEmailVerification).text =
                "Sign in to check your email verification status."

            tv(R.id.btnSettingsVerifyEmail).text = "Verify"
            return
        }

        tv(R.id.tvSettingsEmailVerification).text =
            if (user.isEmailVerified) {
                "Your email address is verified."
            } else {
                "Your email address has not been verified."
            }

        tv(R.id.btnSettingsVerifyEmail).text =
            if (user.isEmailVerified) "Verified" else "Verify"
    }

    private fun sendEmailVerification() {
        val user = getFirebaseAuth()?.currentUser

        if (user == null) {
            showError("You need to sign in before verifying your email.")
            return
        }

        if (user.isEmailVerified) {
            showSuccess("Your email address is already verified.")
            return
        }

        user.sendEmailVerification()
            .addOnSuccessListener {
                showSuccess("Verification email sent to ${user.email}.")
            }
            .addOnFailureListener { exception ->
                showError(
                    exception.localizedMessage ?: "Unable to send verification email."
                )
            }
    }

    // ---------------------------------------------------------
    // PASSWORD RESET
    // ---------------------------------------------------------

    private fun showPasswordResetDialog() {
        val currentEmail = getFirebaseAuth()?.currentUser?.email
            ?: settingsPrefs.getString("email", null)

        if (!currentEmail.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage("Send a password reset email to $currentEmail?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send Email") { _, _ ->
                    sendPasswordResetEmail(currentEmail)
                }
                .show()
            return
        }

        val input = EditText(this).apply {
            hint = "Enter your account email"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(35, 25, 35, 25)
        }

        AlertDialog.Builder(this)
            .setTitle("Reset Password")
            .setMessage("Enter the email address associated with your account.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send Email") { _, _ ->
                val email = input.text.toString().trim()

                if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS
                        .matcher(email).matches()
                ) {
                    showError("Please enter a valid email address.")
                } else {
                    sendPasswordResetEmail(email)
                }
            }
            .show()
    }

    private fun sendPasswordResetEmail(email: String) {
        val auth = getFirebaseAuth()

        if (auth == null) {
            showError("Firebase Authentication is not configured.")
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                showSuccess("Password reset email sent to $email.")
            }
            .addOnFailureListener { exception ->
                showError(
                    exception.localizedMessage ?: "Unable to send password reset email."
                )
            }
    }

    // ---------------------------------------------------------
    // BIOMETRIC VERIFICATION
    // ---------------------------------------------------------

    private fun requestBiometricEnable() {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val biometricManager = BiometricManager.from(this)
        val availability = biometricManager.canAuthenticate(authenticators)

        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            setBiometricSwitch(false)

            val message = when (availability) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "This device does not have biometric hardware."

                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware is currently unavailable."

                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Set up a fingerprint, face unlock, or device screen lock first."

                else ->
                    "Biometric authentication is not available on this device."
            }

            tv(R.id.tvSettingsSecurityStatus).text = message
            showError(message)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)

        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)

                    settingsPrefs.edit()
                        .putBoolean(KEY_BIOMETRIC, true)
                        .apply()

                    setBiometricSwitch(true)
                    updateBiometricStatus()

                    showSuccess(
                        "Biometric verification completed. Preference saved."
                    )
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)

                    settingsPrefs.edit()
                        .putBoolean(KEY_BIOMETRIC, false)
                        .apply()

                    setBiometricSwitch(false)
                    tv(R.id.tvSettingsSecurityStatus).text =
                        "Biometric preference is disabled."

                    showError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        this@SettingsActivity,
                        "Biometric not recognized. Try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable Biometric Verification")
            .setSubtitle("Verify your identity to enable this preference.")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun setBiometricSwitch(checked: Boolean) {
        updatingBiometricSwitch = true
        switch(R.id.switchBiometricLock).isChecked = checked
        updatingBiometricSwitch = false
    }

    private fun updateBiometricStatus() {
        val enabled = settingsPrefs.getBoolean(KEY_BIOMETRIC, false)

        tv(R.id.tvSettingsSecurityStatus).text =
            if (enabled) {
                "Biometric verification was enabled on this device. " +
                        "Other screens must also check this preference to enforce an app-wide lock."
            } else {
                "Biometric verification is currently disabled."
            }
    }

    // ---------------------------------------------------------
    // FIREBASE CLOUD BACKUP
    // ---------------------------------------------------------

    private fun refreshSyncStatus() {
        val user = getFirebaseAuth()?.currentUser

        if (!firebaseIsConfigured()) {
            tv(R.id.tvSettingsSyncStatus).text =
                "Firebase is not configured. Your records remain on this device."

            tv(R.id.tvSettingsSyncIndicator).text = "LOCAL"
            updateLastSyncLabel()
            return
        }

        if (user == null) {
            tv(R.id.tvSettingsSyncStatus).text =
                "Sign in to your account to back up your records."

            tv(R.id.tvSettingsSyncIndicator).text = "OFF"
            updateLastSyncLabel()
            return
        }

        tv(R.id.tvSettingsSyncStatus).text =
            "Signed in as ${user.email ?: "your account"}. Cloud backup is available."

        tv(R.id.tvSettingsSyncIndicator).text = "READY"
        updateLastSyncLabel()
    }

    private fun localBackupModules(): List<Pair<String, SharedPreferences>> {
        return listOf(
            "app_settings" to getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE),
            "salary" to getSharedPreferences(SALARY_PREFS, MODE_PRIVATE),
            "savings" to getSharedPreferences(SAVINGS_PREFS, MODE_PRIVATE),
            "obligations" to getSharedPreferences(OBLIGATIONS_PREFS, MODE_PRIVATE)
        )
    }

    private fun sharedPreferencesToJson(
        preferences: SharedPreferences
    ): String {
        val json = JSONObject()

        preferences.all.toSortedMap().forEach { (key, value) ->
            when (value) {
                is Set<*> -> {
                    val array = JSONArray()
                    value.forEach { item -> array.put(item) }
                    json.put(key, array)
                }

                else -> json.put(key, value)
            }
        }

        return json.toString()
    }

    private fun backupLocalDataToFirebase() {
        val user = getFirebaseAuth()?.currentUser
        val firestore = getFirestore()

        if (user == null) {
            showError("Sign in to Firebase before backing up your data.")
            return
        }

        if (firestore == null) {
            showError("Cloud Firestore is not configured in this app.")
            return
        }

        val modules = localBackupModules()
        val snapshots = modules.map { module ->
            module.first to sharedPreferencesToJson(module.second)
        }

        // Leave room below Firestore's document-size limit.
        val oversizedModule = snapshots.firstOrNull {
            it.second.toByteArray(Charsets.UTF_8).size > 800_000
        }

        if (oversizedModule != null) {
            showError(
                "The ${oversizedModule.first} backup is too large for one document. " +
                        "It needs to be split into smaller records before upload."
            )
            return
        }

        setSyncBusy(true)
        tv(R.id.tvSettingsSyncStatus).text = "Uploading your local backup..."
        tv(R.id.tvSettingsSyncIndicator).text = "SYNCING"

        val backupDocument = firestore
            .collection("users")
            .document(user.uid)
            .collection("deviceBackups")
            .document("latest")

        val moduleTasks = snapshots.map { (moduleName, jsonData) ->
            val moduleDocument = backupDocument
                .collection("modules")
                .document(moduleName)

            val documentData = hashMapOf<String, Any>(
                "module" to moduleName,
                "dataJson" to jsonData,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            moduleDocument.set(documentData, SetOptions.merge())
        }

        Tasks.whenAllComplete(moduleTasks)
            .addOnCompleteListener {
                val failedTask = moduleTasks.firstOrNull { task -> !task.isSuccessful }

                if (failedTask != null) {
                    setSyncBusy(false)
                    tv(R.id.tvSettingsSyncStatus).text =
                        "Cloud backup failed. Check your connection and Firestore rules."

                    tv(R.id.tvSettingsSyncIndicator).text = "ERROR"

                    showError(
                        failedTask.exception?.localizedMessage
                            ?: "One or more data groups could not be uploaded."
                    )
                    return@addOnCompleteListener
                }

                val metadata = hashMapOf<String, Any>(
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "deviceModel" to Build.MODEL,
                    "moduleCount" to snapshots.size
                )

                backupDocument.set(metadata, SetOptions.merge())
                    .addOnSuccessListener {
                        settingsPrefs.edit()
                            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                            .apply()

                        setSyncBusy(false)

                        tv(R.id.tvSettingsSyncStatus).text =
                            "Cloud backup completed for ${snapshots.size} data groups."

                        tv(R.id.tvSettingsSyncIndicator).text = "SYNCED"

                        updateLastSyncLabel()
                        showSuccess("Your local backup was uploaded to Firebase.")
                    }
                    .addOnFailureListener { exception ->
                        setSyncBusy(false)
                        tv(R.id.tvSettingsSyncIndicator).text = "ERROR"

                        showError(
                            exception.localizedMessage
                                ?: "Unable to save cloud backup information."
                        )
                    }
            }
    }

    private fun setSyncBusy(busy: Boolean) {
        findViewById<ProgressBar>(R.id.settingsSyncProgress).visibility =
            if (busy) View.VISIBLE else View.GONE

        button(R.id.btnSettingsSyncNow).isEnabled = !busy
        button(R.id.btnSettingsSyncNow).text =
            if (busy) "Backing Up..." else "Sync Data Now"
    }

    private fun updateLastSyncLabel() {
        val lastSync = settingsPrefs.getLong(KEY_LAST_SYNC, 0L)

        val message = if (lastSync > 0L) {
            val formatted = SimpleDateFormat(
                "MMM d, yyyy • h:mm a",
                Locale.getDefault()
            ).format(Date(lastSync))

            "Last successful sync: $formatted"
        } else {
            "Last successful sync: Not available"
        }

        tv(R.id.tvSettingsLastSync).text = message
    }

    // ---------------------------------------------------------
    // CSV EXPORT
    // ---------------------------------------------------------

    private fun exportFinancialData(uri: Uri) {
        try {
            val csv = buildFinancialCsv()

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(csv)
                    writer.flush()
                }
            } ?: throw IllegalStateException("Unable to open the selected file.")

            tv(R.id.tvSettingsDataStatus).text =
                "Financial data exported successfully."

            tv(R.id.tvSettingsDataStatus).visibility = View.VISIBLE
            showSuccess("CSV backup exported successfully.")

        } catch (exception: Exception) {
            showError(
                exception.localizedMessage ?: "Unable to export financial data."
            )
        }
    }

    private fun buildFinancialCsv(): String {
        val result = StringBuilder()

        result.append("Module,Key,Value\n")

        localBackupModules().forEach { (moduleName, preferences) ->
            preferences.all.toSortedMap().forEach { (key, value) ->
                result.append(csvEscape(moduleName))
                    .append(",")
                    .append(csvEscape(key))
                    .append(",")
                    .append(csvEscape(valueToExport(value)))
                    .append("\n")
            }
        }

        return result.toString()
    }

    private fun valueToExport(value: Any?): String {
        return when (value) {
            null -> ""
            is Set<*> -> value.joinToString(";")
            else -> value.toString()
        }
    }

    private fun csvEscape(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    // ---------------------------------------------------------
    // CLEAR TEMPORARY CACHE
    // ---------------------------------------------------------

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear Temporary Cache")
            .setMessage(
                "This removes temporary cached files. It will not delete your salary, " +
                        "expense, savings, loan, or obligation records."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Cache") { _, _ ->
                clearTemporaryCache()
            }
            .show()
    }

    private fun clearTemporaryCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }

            externalCacheDir?.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }

            tv(R.id.tvSettingsDataStatus).text =
                "Temporary cache cleared. Financial records were not deleted."

            tv(R.id.tvSettingsDataStatus).visibility = View.VISIBLE
            showSuccess("Temporary cache cleared.")

        } catch (exception: Exception) {
            showError(
                exception.localizedMessage ?: "Unable to clear temporary cache."
            )
        }
    }

    // ---------------------------------------------------------
    // PRIVACY AND TERMS
    // ---------------------------------------------------------

    private fun showPrivacyInformation() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & Data Information")
            .setMessage(
                "My Finance stores financial records locally using this app's private " +
                        "storage. When Firebase backup is used, the supported local data " +
                        "groups are uploaded to the signed-in user's Firebase account.\n\n" +
                        "Keep your device secure and do not share your account credentials. " +
                        "Export important records regularly. Cloud backup availability " +
                        "depends on your Firebase project configuration and security rules."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTermsInformation() {
        AlertDialog.Builder(this)
            .setTitle("Terms & Conditions")
            .setMessage(
                "My Finance is a personal record-keeping tool. You are responsible for " +
                        "the accuracy of the salary, expense, savings, loan, and payment " +
                        "information you enter.\n\n" +
                        "The app does not replace professional financial, tax, or legal advice. " +
                        "Keep your own backups of important financial information."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------
    // LOG OUT
    // ---------------------------------------------------------

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage(
                "Are you sure you want to log out? Your locally saved financial " +
                        "records will remain on this device."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Log Out") { _, _ ->
                getFirebaseAuth()?.signOut()

                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra("logged_out", true)
                )

                Toast.makeText(
                    this,
                    "You have been logged out.",
                    Toast.LENGTH_SHORT
                ).show()

                finish()
            }
            .show()
    }

    // ---------------------------------------------------------
    // DELETE ACCOUNT
    // ---------------------------------------------------------

    private fun confirmDeleteAccount() {
        val user = getFirebaseAuth()?.currentUser

        if (user == null) {
            showError("You need to be signed in to delete your account.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete My Account?")
            .setMessage(
                "This permanently deletes your Firebase Authentication account and " +
                        "the latest cloud backup created by this Settings screen.\n\n" +
                        "Financial records stored locally on this device will NOT be deleted. " +
                        "Other Firestore data paths, if your app uses them, may need separate " +
                        "cleanup.\n\nAre you sure you want to continue?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete Account") { _, _ ->
                deleteFirebaseAccount(user)
            }
            .show()
    }

    private fun deleteFirebaseAccount(user: com.google.firebase.auth.FirebaseUser) {
        val firestore = getFirestore()

        if (firestore == null) {
            showError("Cloud Firestore is unavailable. Account deletion was not started.")
            return
        }

        findViewById<ProgressBar>(R.id.settingsProgress).visibility = View.VISIBLE

        val backupDocument = firestore
            .collection("users")
            .document(user.uid)
            .collection("deviceBackups")
            .document("latest")

        val moduleNames = listOf(
            "app_settings",
            "salary",
            "savings",
            "obligations"
        )

        val deleteTasks: List<Task<Void>> = moduleNames.map { moduleName ->
            backupDocument.collection("modules")
                .document(moduleName)
                .delete()
        } + backupDocument.delete()

        Tasks.whenAllComplete(deleteTasks)
            .addOnCompleteListener {
                val failedDelete = deleteTasks.firstOrNull { task -> !task.isSuccessful }

                if (failedDelete != null) {
                    findViewById<ProgressBar>(R.id.settingsProgress).visibility =
                        View.GONE

                    showError(
                        "The cloud backup could not be fully deleted. " +
                                "Your sign-in account was not deleted."
                    )
                    return@addOnCompleteListener
                }

                user.delete()
                    .addOnSuccessListener {
                        getFirebaseAuth()?.signOut()

                        // Remove only local profile preferences.
                        // Financial records remain on this device.
                        settingsPrefs.edit()
                            .remove(KEY_FULL_NAME)
                            .remove(KEY_USERNAME)
                            .apply()

                        Toast.makeText(
                            this,
                            "Account deleted. Local financial records remain on this device.",
                            Toast.LENGTH_LONG
                        ).show()

                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra("account_deleted", true)
                        )

                        finish()
                    }
                    .addOnFailureListener { exception ->
                        findViewById<ProgressBar>(R.id.settingsProgress).visibility =
                            View.GONE

                        showError(
                            exception.localizedMessage
                                ?: "Account deletion failed. You may need to sign in again."
                        )
                    }
            }
    }

    // ---------------------------------------------------------
    // APP VERSION
    // ---------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun updateAppVersion() {
        try {
            val version = packageManager
                .getPackageInfo(packageName, 0)
                .versionName ?: "1.0.0"

            tv(R.id.tvSettingsAppVersion).text = "v$version"
        } catch (_: Exception) {
            tv(R.id.tvSettingsAppVersion).text = "v1.0.0"
        }
    }

    // ---------------------------------------------------------
    // GENERAL STATUS MESSAGES
    // ---------------------------------------------------------

    private fun showError(message: String) {
        tv(R.id.tvSettingsSuccess).visibility = View.GONE

        tv(R.id.tvSettingsError).text = message
        tv(R.id.tvSettingsError).visibility = View.VISIBLE

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showSuccess(message: String) {
        tv(R.id.tvSettingsError).visibility = View.GONE

        tv(R.id.tvSettingsSuccess).text = message
        tv(R.id.tvSettingsSuccess).visibility = View.VISIBLE

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------
    // SPINNER HELPERS
    // ---------------------------------------------------------

    private fun selectSpinnerValue(spinnerId: Int, value: String) {
        val target = spinner(spinnerId)
        val adapter = target.adapter ?: return

        for (index in 0 until adapter.count) {
            if (adapter.getItem(index)?.toString() == value) {
                target.setSelection(index)
                return
            }
        }

        target.setSelection(0)
    }

    // ---------------------------------------------------------
    // VIEW HELPERS
    // ---------------------------------------------------------

    private fun tv(id: Int): TextView = findViewById(id)

    private fun et(id: Int): EditText = findViewById(id)

    private fun button(id: Int): Button = findViewById(id)

    private fun spinner(id: Int): Spinner = findViewById(id)

    private fun switch(id: Int): SwitchCompat = findViewById(id)
}

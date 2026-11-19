package com.example.moneytracker

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.Locale
import java.util.UUID

class SavingsActivity : AppCompatActivity() {

    // ==========================================
    // DATA MODEL
    // ==========================================

    private data class SavingsRecord(
        val id: String,
        val type: String,
        val account: String,
        val amount: Double,
        val date: Long,
        val source: String,
        val reference: String,
        val notes: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private data class PendingOperation(
        val id: String,
        val action: String,
        val updatedAt: Long
    )

    // ==========================================
    // DATA STORAGE
    // ==========================================

    private val savingsRecords = mutableListOf<SavingsRecord>()
    private val pendingOperations = linkedMapOf<String, PendingOperation>()
    private val operationsInFlight = mutableSetOf<String>()

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userId: String? = null
    private var redirectedToAuth = false
    private var hasLoadedCloud = false

    private val prefs by lazy {
        getSharedPreferences("money_tracker_savings", MODE_PRIVATE)
    }

    private fun recordsStorageKey(uid: String) = "savings_records_$uid"
    private fun pendingStorageKey(uid: String) = "savings_pending_$uid"

    private val manilaTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private companion object {
        const val ACTION_UPSERT = "upsert"
        const val ACTION_DELETE = "delete"
        const val LEGACY_MIGRATED_OWNER_KEY = "savings_legacy_migrated_owner"
    }

    private val currencyFormatter =
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    private val monthFormatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).apply {
        timeZone = manilaTimeZone
    }

    private val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply {
        timeZone = manilaTimeZone
    }

    private val isoDateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = manilaTimeZone
    }

    private val savingsCalendar = Calendar.getInstance(manilaTimeZone)

    private var selectedTransactionDate: Long? = null

    private val contributionType = "Contribution"
    private val withdrawalType = "Withdrawal"

    // ==========================================
    // FORM VIEWS
    // ==========================================

    private lateinit var savingsFormCard: View

    private lateinit var etSavingsId: EditText
    private lateinit var etSavingsAccount: EditText
    private lateinit var etSavingsAmount: EditText
    private lateinit var etSavingsSource: EditText
    private lateinit var etSavingsReference: EditText
    private lateinit var etSavingsNotes: EditText

    private lateinit var spSavingsTransactionType: Spinner
    private lateinit var spSavingsFilterType: Spinner

    private lateinit var tvSavingsDate: TextView
    private lateinit var tvSavingsTypeExplanation: TextView
    private lateinit var tvSavingsPreviewChange: TextView
    private lateinit var tvSavingsPreviewCashImpact: TextView
    private lateinit var tvSavingsPreviewExplanation: TextView
    private lateinit var tvSavingsFormError: TextView
    private lateinit var tvSavingsSyncStatus: TextView

    // ==========================================
    // ACTIVITY
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_savings)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupWindowInsets()
        initializeViews()
        setupSpinners()
        setupClickListeners()
        setupTextWatchers()

        if (!requireAuthenticatedUser()) return

        val uid = userId ?: return
        migrateLegacyLocalRecords(uid)
        loadSavingsRecords(uid)
        updateMonthDisplay()
        updateTransactionPreview()
        renderAll()
        updateSyncStatus("Saved on this device · Checking Firebase…")

        loadCloudRecords()
        syncPendingOperations()
    }

    override fun onResume() {
        super.onResume()
        if (!::auth.isInitialized || userId == null || redirectedToAuth) return

        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid != userId) {
            redirectToLogin()
            return
        }
        syncPendingOperations()
    }

    private fun requireAuthenticatedUser(): Boolean {
        val user = auth.currentUser
        if (user == null) {
            redirectToLogin()
            return false
        }
        if (!user.isEmailVerified) {
            redirectToEmailVerification()
            return false
        }
        userId = user.uid
        return true
    }

    private fun redirectToLogin() {
        if (redirectedToAuth) return
        redirectedToAuth = true
        startActivity(Intent(this, Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun redirectToEmailVerification() {
        if (redirectedToAuth) return
        redirectedToAuth = true
        startActivity(Intent(this, EmailVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ==========================================
    // WINDOW INSETS
    // ==========================================

    private fun setupWindowInsets() {

        val mainView = findViewById<View>(R.id.main)

        ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->

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
    // INITIALIZE VIEWS
    // ==========================================

    private fun initializeViews() {

        savingsFormCard = findViewById(
            R.id.savingsFormCard
        )

        etSavingsId = findViewById(
            R.id.etSavingsId
        )

        etSavingsAccount = findViewById(
            R.id.etSavingsAccount
        )

        etSavingsAmount = findViewById(
            R.id.etSavingsAmount
        )

        etSavingsSource = findViewById(
            R.id.etSavingsSource
        )

        etSavingsReference = findViewById(
            R.id.etSavingsReference
        )

        etSavingsNotes = findViewById(
            R.id.etSavingsNotes
        )

        spSavingsTransactionType = findViewById(
            R.id.spSavingsTransactionType
        )

        spSavingsFilterType = findViewById(
            R.id.spSavingsFilterType
        )

        tvSavingsDate = findViewById(
            R.id.tvSavingsDate
        )

        tvSavingsTypeExplanation = findViewById(
            R.id.tvSavingsTypeExplanation
        )

        tvSavingsPreviewChange = findViewById(
            R.id.tvSavingsPreviewChange
        )

        tvSavingsPreviewCashImpact = findViewById(
            R.id.tvSavingsPreviewCashImpact
        )

        tvSavingsPreviewExplanation = findViewById(
            R.id.tvSavingsPreviewExplanation
        )

        tvSavingsFormError = findViewById(
            R.id.tvSavingsFormError
        )

        tvSavingsSyncStatus = findViewById(R.id.tvSavingsSyncStatus)
    }

    // ==========================================
    // SPINNERS
    // ==========================================

    private fun setupSpinners() {

        setSpinnerItems(
            spSavingsTransactionType,
            listOf(
                contributionType,
                withdrawalType
            )
        )

        setSpinnerItems(
            spSavingsFilterType,
            listOf(
                "All Transactions",
                contributionType,
                withdrawalType
            )
        )

        spSavingsTransactionType.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateTransactionExplanation()
                    updateTransactionPreview()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}
            }

        spSavingsFilterType.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    renderSavingsRecords()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}
            }
    }

    private fun setSpinnerItems(
        spinner: Spinner,
        items: List<String>
    ) {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter
    }

    // ==========================================
    // CLICK LISTENERS
    // ==========================================

    private fun setupClickListeners() {

        // Back
        findViewById<View>(
            R.id.btnSavingsBack
        ).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnRefreshSavings).setOnClickListener {
            refreshCloudRecords()
        }

        // Add savings
        findViewById<View>(
            R.id.btnAddSavings
        ).setOnClickListener {
            showSavingsForm()
        }

        // Close form
        findViewById<View>(
            R.id.btnCloseSavingsForm
        ).setOnClickListener {
            savingsFormCard.visibility = View.GONE
        }

        // Save record
        findViewById<View>(
            R.id.btnSaveSavings
        ).setOnClickListener {
            saveSavingsRecord()
        }

        // Date picker
        tvSavingsDate.setOnClickListener {
            showSavingsDatePicker()
        }

        // Previous month
        findViewById<View>(
            R.id.btnPreviousSavingsMonth
        ).setOnClickListener {

            savingsCalendar.add(
                Calendar.MONTH,
                -1
            )

            updateMonthDisplay()
            renderAll()
        }

        // Next month
        findViewById<View>(
            R.id.btnNextSavingsMonth
        ).setOnClickListener {

            val next = savingsCalendar.clone() as Calendar

            next.add(Calendar.MONTH, 1)

            val today = Calendar.getInstance(manilaTimeZone)

            if (
                next.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
                (
                        next.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                next.get(Calendar.MONTH) > today.get(Calendar.MONTH)
                        )
            ) {

                showMessage(
                    "Future months are not available yet."
                )

                return@setOnClickListener
            }

            savingsCalendar.add(
                Calendar.MONTH,
                1
            )

            updateMonthDisplay()
            renderAll()
        }

        // Clear filters
        findViewById<View>(
            R.id.btnClearSavingsFilters
        ).setOnClickListener {

            findViewById<EditText>(
                R.id.etSavingsSearch
            ).setText("")

            spSavingsFilterType.setSelection(0)

            renderSavingsRecords()
        }
    }

    // ==========================================
    // TEXT WATCHERS
    // ==========================================

    private fun setupTextWatchers() {

        etSavingsAmount.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateTransactionPreview()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {}
            }
        )

        findViewById<EditText>(
            R.id.etSavingsSearch
        ).addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    renderSavingsRecords()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {}
            }
        )
    }

    // ==========================================
    // MONTH DISPLAY
    // ==========================================

    private fun updateMonthDisplay() {

        findViewById<TextView>(
            R.id.tvSavingsMonth
        ).text = monthFormatter.format(
            savingsCalendar.time
        )
    }

    private fun isInSelectedMonth(
        timestamp: Long
    ): Boolean {

        val date = Calendar.getInstance(manilaTimeZone)

        date.timeInMillis = timestamp

        return date.get(Calendar.YEAR) ==
                savingsCalendar.get(Calendar.YEAR) &&
                date.get(Calendar.MONTH) ==
                savingsCalendar.get(Calendar.MONTH)
    }

    private fun getSelectedMonthRecords(): List<SavingsRecord> {

        return savingsRecords.filter {
            isInSelectedMonth(it.date)
        }
    }

    // ==========================================
    // SHOW SAVINGS FORM
    // ==========================================

    private fun showSavingsForm(
        id: String? = null
    ) {

        tvSavingsFormError.visibility = View.GONE

        if (id == null) {

            clearSavingsForm()

            findViewById<TextView>(
                R.id.tvSavingsFormTitle
            ).text = "Add Savings Record"

        } else {

            val record = savingsRecords.find {
                it.id == id
            } ?: return

            etSavingsId.setText(record.id)

            etSavingsAccount.setText(record.account)

            etSavingsAmount.setText(
                record.amount.toString()
            )

            etSavingsSource.setText(record.source)

            etSavingsReference.setText(
                record.reference
            )

            etSavingsNotes.setText(record.notes)

            selectedTransactionDate = record.date

            tvSavingsDate.text =
                formatDate(record.date)

            spSavingsTransactionType.setSelection(
                if (record.type == withdrawalType) 1 else 0
            )

            findViewById<TextView>(
                R.id.tvSavingsFormTitle
            ).text = "Edit Savings Record"
        }

        updateTransactionPreview()

        savingsFormCard.visibility = View.VISIBLE

        savingsFormCard.post {

            findViewById<ScrollView>(
                R.id.savingsScroll
            ).smoothScrollTo(
                0,
                savingsFormCard.top
            )
        }
    }

    // ==========================================
    // CLEAR FORM
    // ==========================================

    private fun clearSavingsForm() {

        etSavingsId.setText("")

        etSavingsAccount.setText("")
        etSavingsAmount.setText("")

        etSavingsSource.setText("")
        etSavingsReference.setText("")
        etSavingsNotes.setText("")

        selectedTransactionDate = null

        tvSavingsDate.text =
            "Select transaction date"

        spSavingsTransactionType.setSelection(0)

        updateTransactionPreview()
    }

    // ==========================================
    // TRANSACTION DATE
    // ==========================================

    private fun showSavingsDatePicker() {

        val calendar = Calendar.getInstance(manilaTimeZone)

        selectedTransactionDate?.let {
            calendar.timeInMillis = it
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->

                val selected = Calendar.getInstance(manilaTimeZone)

                selected.set(
                    year,
                    month,
                    day,
                    0,
                    0,
                    0
                )

                selected.set(
                    Calendar.MILLISECOND,
                    0
                )

                selectedTransactionDate =
                    selected.timeInMillis

                tvSavingsDate.text =
                    formatDate(selected.timeInMillis)

                tvSavingsFormError.visibility =
                    View.GONE

            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ==========================================
    // TRANSACTION EXPLANATION
    // ==========================================

    private fun updateTransactionExplanation() {

        val type = spSavingsTransactionType.selectedItem
            ?.toString() ?: contributionType

        when (type) {

            contributionType -> {

                tvSavingsTypeExplanation.text =
                    "A contribution moves money into savings. It is not an expense."

                tvSavingsTypeExplanation.setTextColor(
                    Color.parseColor("#149B71")
                )
            }

            withdrawalType -> {

                tvSavingsTypeExplanation.text =
                    "A withdrawal removes money from savings and returns it to available cash. It is not income."

                tvSavingsTypeExplanation.setTextColor(
                    Color.parseColor("#D85D68")
                )
            }
        }
    }

    // ==========================================
    // TRANSACTION PREVIEW
    // ==========================================

    private fun updateTransactionPreview() {

        val amount = getAmount(etSavingsAmount)

        val type = spSavingsTransactionType.selectedItem
            ?.toString() ?: contributionType

        when (type) {

            contributionType -> {

                tvSavingsPreviewChange.text =
                    "+${formatMoney(amount)}"

                tvSavingsPreviewCashImpact.text =
                    "-${formatMoney(amount)}"

                tvSavingsPreviewChange.setTextColor(
                    Color.parseColor("#149B71")
                )

                tvSavingsPreviewCashImpact.setTextColor(
                    Color.parseColor("#D85D68")
                )

                tvSavingsPreviewExplanation.text =
                    "Your savings balance increases while available cash decreases."
            }

            withdrawalType -> {

                tvSavingsPreviewChange.text =
                    "-${formatMoney(amount)}"

                tvSavingsPreviewCashImpact.text =
                    "+${formatMoney(amount)}"

                tvSavingsPreviewChange.setTextColor(
                    Color.parseColor("#D85D68")
                )

                tvSavingsPreviewCashImpact.setTextColor(
                    Color.parseColor("#149B71")
                )

                tvSavingsPreviewExplanation.text =
                    "Your savings balance decreases while available cash increases."
            }
        }
    }

    // ==========================================
    // SAVE TRANSACTION
    // ==========================================

    private fun saveSavingsRecord() {

        val id = etSavingsId.text.toString().trim()

        val type = spSavingsTransactionType.selectedItem
            ?.toString() ?: ""

        val account = etSavingsAccount.text.toString().trim()

        val amount = getAmount(etSavingsAmount)

        val date = selectedTransactionDate

        val source = etSavingsSource.text.toString().trim()

        val reference = etSavingsReference.text.toString().trim()

        val notes = etSavingsNotes.text.toString().trim()

        val error = when {

            type != contributionType &&
                    type != withdrawalType ->
                "Please select a valid transaction type."

            account.isBlank() ->
                "Please enter a savings account or wallet name."

            amount <= 0.0 ->
                "Please enter an amount greater than zero."

            date == null ->
                "Please select a transaction date."

            else -> null
        }

        if (error != null) {

            showFormError(error)

            return
        }

        val currentRecord = savingsRecords.find {
            it.id == id && id.isNotBlank()
        }

        // Calculate available balance without including
        // the existing transaction when editing.
        val currentBalance = getAccountBalance(
            account,
            excludedRecordId = id
        )

        if (
            type == withdrawalType &&
            amount > currentBalance + 0.009
        ) {

            showFormError(
                "Insufficient savings balance. Available in ${account}: ${formatMoney(currentBalance)}."
            )

            return
        }

        val record = SavingsRecord(
            id = if (id.isBlank()) {
                UUID.randomUUID().toString()
            } else {
                id
            },
            type = type,
            account = account,
            amount = amount,
            date = date!!,
            source = source,
            reference = reference,
            notes = notes,
            updatedAt = System.currentTimeMillis()
        )

        val existingIndex = savingsRecords.indexOfFirst {
            it.id == id && id.isNotBlank()
        }

        if (existingIndex >= 0) {

            savingsRecords[existingIndex] = record

        } else {

            savingsRecords.add(record)
        }

        saveSavingsRecords()
        markPendingUpsert(record)
        syncPendingOperations()

        // Move the displayed month to the transaction's month.
        savingsCalendar.timeInMillis = record.date

        savingsFormCard.visibility = View.GONE

        updateMonthDisplay()
        renderAll()

        showMessage(
            if (existingIndex >= 0) {
                "Savings record updated successfully."
            } else {
                "Savings contribution or withdrawal saved successfully."
            }
        )
    }

    private fun showFormError(message: String) {

        tvSavingsFormError.text = message

        tvSavingsFormError.visibility = View.VISIBLE
    }

    // ==========================================
    // ACCOUNT BALANCE CALCULATION
    // ==========================================

    private fun getAccountBalance(
        account: String,
        excludedRecordId: String = ""
    ): Double {

        return savingsRecords
            .filter {
                it.account.equals(
                    account.trim(),
                    ignoreCase = true
                ) && it.id != excludedRecordId
            }
            .sumOf { record ->

                when (record.type) {

                    contributionType -> record.amount

                    withdrawalType -> -record.amount

                    else -> 0.0
                }
            }
    }

    private fun getTotalSavingsBalance(): Double {

        return savingsRecords.sumOf { record ->

            when (record.type) {

                contributionType -> record.amount

                withdrawalType -> -record.amount

                else -> 0.0
            }
        }
    }

    // ==========================================
    // SUMMARY STATISTICS
    // ==========================================

    private fun updateStatistics() {

        val allContributions = savingsRecords
            .filter {
                it.type == contributionType
            }
            .sumOf {
                it.amount
            }

        val allWithdrawals = savingsRecords
            .filter {
                it.type == withdrawalType
            }
            .sumOf {
                it.amount
            }

        val currentBalance = getTotalSavingsBalance()

        val monthRecords = getSelectedMonthRecords()

        val monthlyContributions = monthRecords
            .filter {
                it.type == contributionType
            }
            .sumOf {
                it.amount
            }

        val monthlyWithdrawals = monthRecords
            .filter {
                it.type == withdrawalType
            }
            .sumOf {
                it.amount
            }

        val netAdded =
            monthlyContributions - monthlyWithdrawals

        val accountsCount = savingsRecords
            .map {
                it.account.trim().lowercase(Locale.ROOT)
            }
            .distinct()
            .size

        findViewById<TextView>(
            R.id.tvTotalSavingsBalance
        ).text = formatMoney(currentBalance)

        findViewById<TextView>(
            R.id.tvSavingsBalanceLabel
        ).text = "All accounts"

        findViewById<TextView>(
            R.id.tvSavingsBalanceDescription
        ).text =
            "Current balance across $accountsCount savings account(s)."

        findViewById<TextView>(
            R.id.tvTotalSavingsContributions
        ).text = formatMoney(allContributions)

        findViewById<TextView>(
            R.id.tvTotalSavingsWithdrawals
        ).text = formatMoney(allWithdrawals)

        findViewById<TextView>(
            R.id.tvMonthlySavingsContributions
        ).text = formatMoney(monthlyContributions)

        findViewById<TextView>(
            R.id.tvMonthlySavingsWithdrawals
        ).text = formatMoney(monthlyWithdrawals)

        findViewById<TextView>(
            R.id.tvMonthlyNetSavings
        ).text = formatMoney(netAdded)

        findViewById<TextView>(
            R.id.tvSavingsLifetimeContributions
        ).text = formatMoney(allContributions)

        findViewById<TextView>(
            R.id.tvSavingsLifetimeWithdrawals
        ).text = formatMoney(allWithdrawals)

        findViewById<TextView>(
            R.id.tvSavingsBreakdownBalance
        ).text = formatMoney(currentBalance)

        findViewById<TextView>(
            R.id.tvSavingsAccountCount
        ).text = "$accountsCount accounts"
    }

    // ==========================================
    // ACCOUNT BREAKDOWN
    // ==========================================

    private fun renderSavingsAccounts() {

        val container = findViewById<LinearLayout>(
            R.id.savingsAccountsContainer
        )

        val emptyState = findViewById<View>(
            R.id.savingsAccountsEmptyState
        )

        container.removeAllViews()

        val groupedAccounts = savingsRecords.groupBy {
            it.account.trim().lowercase(Locale.ROOT)
        }

        val accounts = groupedAccounts.values
            .mapNotNull { records ->

                val accountName = records.firstOrNull()?.account
                    ?: return@mapNotNull null

                val contributions = records
                    .filter {
                        it.type == contributionType
                    }
                    .sumOf {
                        it.amount
                    }

                val withdrawals = records
                    .filter {
                        it.type == withdrawalType
                    }
                    .sumOf {
                        it.amount
                    }

                val balance = contributions - withdrawals

                AccountSummary(
                    name = accountName,
                    contributions = contributions,
                    withdrawals = withdrawals,
                    balance = balance
                )
            }
            .sortedBy {
                it.name.lowercase(Locale.ROOT)
            }

        emptyState.visibility =
            if (accounts.isEmpty()) View.VISIBLE else View.GONE

        container.visibility =
            if (accounts.isEmpty()) View.GONE else View.VISIBLE

        accounts.forEach { account ->

            container.addView(
                createAccountCard(account)
            )
        }
    }

    private data class AccountSummary(
        val name: String,
        val contributions: Double,
        val withdrawals: Double,
        val balance: Double
    )

    private fun createAccountCard(
        account: AccountSummary
    ): View {

        val card = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(13),
                dp(13),
                dp(13),
                dp(13)
            )

            background = roundedBackground(
                "#F8F8FC",
                "#EAECF2"
            )
        }

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            makeText(
                account.name,
                13f,
                "#202334",
                true
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(
            makeText(
                formatMoney(account.balance),
                15f,
                if (account.balance >= 0) "#149B71" else "#D85D68",
                true
            )
        )

        card.addView(header)

        card.addView(
            makeText(
                "Contributions: ${formatMoney(account.contributions)}",
                10f,
                "#149B71"
            ).apply {
                setPadding(0, dp(9), 0, dp(3))
            }
        )

        card.addView(
            makeText(
                "Withdrawals: ${formatMoney(account.withdrawals)}",
                10f,
                "#D85D68"
            )
        )

        card.addView(
            makeText(
                "Current account balance",
                9f,
                "#858B9E"
            ).apply {
                setPadding(0, dp(7), 0, 0)
            }
        )

        return card.apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                bottomMargin = dp(9)
            }
        }
    }

    // ==========================================
    // SEARCH AND FILTER
    // ==========================================

    private fun getFilteredRecords(): List<SavingsRecord> {

        val search = findViewById<EditText>(
            R.id.etSavingsSearch
        ).text.toString().trim().lowercase(Locale.ROOT)

        val selectedType =
            spSavingsFilterType.selectedItem?.toString()
                ?: "All Transactions"

        return getSelectedMonthRecords()
            .filter { record ->

                val matchesSearch = listOf(
                    record.account,
                    record.type,
                    record.source,
                    record.reference,
                    record.notes,
                    formatDate(record.date),
                    record.amount.toString()
                ).any {
                    it.lowercase(Locale.ROOT).contains(search)
                }

                val matchesType =
                    selectedType == "All Transactions" ||
                            selectedType == record.type

                matchesSearch && matchesType
            }
            .sortedByDescending {
                it.date
            }
    }

    // ==========================================
    // RENDER TRANSACTION RECORDS
    // ==========================================

    private fun renderSavingsRecords() {

        val container = findViewById<LinearLayout>(
            R.id.savingsRecordsContainer
        )

        val emptyState = findViewById<View>(
            R.id.savingsEmptyState
        )

        container.removeAllViews()

        val filtered = getFilteredRecords()

        emptyState.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE

        container.visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE

        findViewById<TextView>(
            R.id.tvSavingsRecordCount
        ).text = "${filtered.size} records"

        findViewById<TextView>(
            R.id.tvSavingsFilterSummary
        ).text = when {

            getSelectedMonthRecords().isEmpty() ->
                "No savings transactions for the selected month."

            filtered.isEmpty() ->
                "No transactions match your current search or filter."

            else ->
                "Showing ${filtered.size} of ${getSelectedMonthRecords().size} transactions."
        }

        filtered.forEach { record ->

            container.addView(
                createSavingsRecordCard(record)
            )
        }
    }

    // ==========================================
    // TRANSACTION CARD
    // ==========================================

    private fun createSavingsRecordCard(
        record: SavingsRecord
    ): View {

        val card = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(13),
                dp(13),
                dp(13),
                dp(13)
            )

            background = roundedBackground(
                "#FFFFFF",
                "#E8EAF0"
            )
        }

        // Header
        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            makeText(
                record.account,
                13f,
                "#202334",
                true
            ),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val isContribution =
            record.type == contributionType

        val typeBadge = makeText(
            record.type,
            9f,
            if (isContribution) "#149B71" else "#D85D68",
            true
        ).apply {

            setPadding(
                dp(9),
                dp(6),
                dp(9),
                dp(6)
            )

            background = roundedBackground(
                if (isContribution) "#E7F7F0" else "#FFF0F0"
            )
        }

        header.addView(typeBadge)

        card.addView(header)

        // Date
        card.addView(
            makeText(
                formatDate(record.date),
                10f,
                "#858B9E"
            ).apply {
                setPadding(0, dp(8), 0, dp(10))
            }
        )

        // Amount
        val amountText = if (isContribution) {
            "+${formatMoney(record.amount)}"
        } else {
            "-${formatMoney(record.amount)}"
        }

        card.addView(
            makeText(
                amountText,
                22f,
                if (isContribution) "#149B71" else "#D85D68",
                true
            ).apply {
                setPadding(0, 0, 0, dp(9))
            }
        )

        // Source
        if (record.source.isNotBlank()) {

            card.addView(
                makeText(
                    "Source / Destination: ${record.source}",
                    10f,
                    "#62687A"
                ).apply {
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }

        // Reference
        if (record.reference.isNotBlank()) {

            card.addView(
                makeText(
                    "Reference: ${record.reference}",
                    10f,
                    "#858B9E"
                ).apply {
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

        // Notes
        if (record.notes.isNotBlank()) {

            card.addView(
                makeText(
                    record.notes,
                    10f,
                    "#858B9E"
                ).apply {
                    setPadding(0, dp(7), 0, 0)
                }
            )
        }

        // Actions
        val actions = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(0, dp(12), 0, 0)
        }

        actions.addView(
            createAction(
                "Edit",
                "#EFEDFF",
                "#6558D3"
            ) {
                showSavingsForm(record.id)
            },
            actionLayoutParams()
        )

        actions.addView(
            createAction(
                "Delete",
                "#FFF0F0",
                "#D85D68"
            ) {
                confirmDeleteRecord(record.id)
            },
            actionLayoutParams()
        )

        card.addView(actions)

        return card.apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    // ==========================================
    // DELETE RECORD
    // ==========================================

    private fun confirmDeleteRecord(id: String) {

        val record = savingsRecords.find {
            it.id == id
        } ?: return

        AlertDialog.Builder(this)
            .setTitle("Delete Savings Record")
            .setMessage(
                "Delete this ${record.type.lowercase()} of ${formatMoney(record.amount)} from ${record.account}? The account balance will be recalculated."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->

                savingsRecords.removeAll {
                    it.id == id
                }

                saveSavingsRecords()
                markPendingDelete(id)
                syncPendingOperations()
                renderAll()

                showMessage(
                    "Savings record deleted. It will sync when Firebase is available."
                )
            }
            .show()
    }

    // ==========================================
    // RENDER ALL
    // ==========================================

    private fun renderAll() {

        findViewById<View>(
            R.id.savingsLoading
        ).visibility = View.GONE

        findViewById<View>(
            R.id.savingsProgress
        ).visibility = View.GONE

        updateStatistics()

        renderSavingsAccounts()

        renderSavingsRecords()
    }

    // ==========================================
    // LOCAL STORAGE - SAVE / LOAD / MIGRATION
    // ==========================================

    private fun migrateLegacyLocalRecords(uid: String) {
        val migratedOwner = prefs.getString(LEGACY_MIGRATED_OWNER_KEY, null)
        if (migratedOwner != null) return

        val userKey = recordsStorageKey(uid)
        if (prefs.contains(userKey)) {
            prefs.edit().putString(LEGACY_MIGRATED_OWNER_KEY, uid).apply()
            return
        }

        val legacyJson = prefs.getString("savings_records", null)
        if (legacyJson.isNullOrBlank()) {
            prefs.edit().putString(LEGACY_MIGRATED_OWNER_KEY, uid).apply()
            return
        }

        try {
            val array = JSONArray(legacyJson)
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").ifBlank { UUID.randomUUID().toString() }
                val record = recordFromJson(json, id) ?: continue
                savingsRecords.add(record)
                pendingOperations[id] = PendingOperation(id, ACTION_UPSERT, record.updatedAt)
            }
            saveLocalState(uid)
            prefs.edit().putString(LEGACY_MIGRATED_OWNER_KEY, uid).apply()
        } catch (exception: Exception) {
            showDataError("Unable to migrate saved savings records: ${exception.localizedMessage ?: "Invalid local data"}")
        }
    }

    private fun loadSavingsRecords(uid: String) {
        savingsRecords.clear()
        pendingOperations.clear()
        try {
            val recordsArray = JSONArray(prefs.getString(recordsStorageKey(uid), "[]"))
            for (index in 0 until recordsArray.length()) {
                val json = recordsArray.optJSONObject(index) ?: continue
                val id = json.optString("id")
                if (id.isBlank()) continue
                recordFromJson(json, id)?.let { savingsRecords.add(it) }
            }

            val pendingArray = JSONArray(prefs.getString(pendingStorageKey(uid), "[]"))
            for (index in 0 until pendingArray.length()) {
                val json = pendingArray.optJSONObject(index) ?: continue
                val id = json.optString("id")
                val action = json.optString("action")
                if (id.isNotBlank() && action in listOf(ACTION_UPSERT, ACTION_DELETE)) {
                    pendingOperations[id] = PendingOperation(
                        id = id,
                        action = action,
                        updatedAt = json.optLong("updatedAt", 0L)
                    )
                }
            }
        } catch (exception: Exception) {
            showDataError("Unable to load local savings records: ${exception.localizedMessage ?: "Invalid local data"}")
        }
    }

    private fun recordFromJson(json: JSONObject, id: String): SavingsRecord? {
        val date = json.optLong("date", 0L)
        if (date <= 0L) return null
        val type = normalizeTransactionType(json.optString("type"))
        return SavingsRecord(
            id = id,
            type = type,
            account = json.optString("account"),
            amount = json.optDouble("amount", 0.0),
            date = date,
            source = json.optString("source", ""),
            reference = json.optString("reference", ""),
            notes = json.optString("notes", ""),
            updatedAt = json.optLong("updatedAt", date)
        )
    }

    private fun saveSavingsRecords() {
        val uid = userId ?: return
        saveLocalState(uid)
    }

    private fun saveLocalState(uid: String) {
        val recordsArray = JSONArray()
        savingsRecords.forEach { record ->
            recordsArray.put(JSONObject().apply {
                put("id", record.id)
                put("type", record.type)
                put("account", record.account)
                put("amount", record.amount)
                put("date", record.date)
                put("source", record.source)
                put("reference", record.reference)
                put("notes", record.notes)
                put("updatedAt", record.updatedAt)
            })
        }

        val pendingArray = JSONArray()
        pendingOperations.values.forEach { operation ->
            pendingArray.put(JSONObject().apply {
                put("id", operation.id)
                put("action", operation.action)
                put("updatedAt", operation.updatedAt)
            })
        }

        prefs.edit()
            .putString(recordsStorageKey(uid), recordsArray.toString())
            .putString(pendingStorageKey(uid), pendingArray.toString())
            .apply()
    }

    // ==========================================
    // FIREBASE LOAD AND MERGE
    // ==========================================

    private fun savingsCollection(): CollectionReference? {
        val uid = userId ?: return null
        return db.collection("users").document(uid).collection("savings")
    }

    private fun refreshCloudRecords() {
        if (userId == null) {
            updateSyncStatus("Sign in to sync savings records")
            return
        }
        findViewById<View>(R.id.savingsProgress).visibility = View.VISIBLE
        findViewById<View>(R.id.btnRefreshSavings).isEnabled = false
        updateSyncStatus("Refreshing savings from Firebase…")
        loadCloudRecords(manualRefresh = true)
    }

    private fun loadCloudRecords(manualRefresh: Boolean = false) {
        val collection = savingsCollection()
        if (collection == null) {
            updateSyncStatus("Local only · Sign in to sync")
            return
        }

        collection.get()
            .addOnSuccessListener { snapshot ->
                hasLoadedCloud = true
                mergeCloudRecords(snapshot)
                renderAll()
                clearDataError()
                findViewById<View>(R.id.btnRefreshSavings).isEnabled = true

                if (snapshot.metadata.isFromCache) {
                    updateSyncStatus("Showing device / Firebase cached data · Offline mode")
                } else if (pendingOperations.isEmpty()) {
                    updateSyncStatus("Synced with Firebase")
                } else {
                    updateSyncStatus("Firebase connected · Syncing pending changes…")
                }
                syncPendingOperations()
                if (manualRefresh && !snapshot.metadata.isFromCache) {
                    showMessage("Savings records refreshed from Firebase.")
                }
            }
            .addOnFailureListener { exception ->
                findViewById<View>(R.id.savingsProgress).visibility = View.GONE
                findViewById<View>(R.id.btnRefreshSavings).isEnabled = true
                updateSyncStatus("Offline · Your local savings records are available")
                showDataError("Could not refresh savings records from Firebase. Your local records are still available. ${exception.localizedMessage ?: ""}")
            }
    }

    private fun mergeCloudRecords(snapshot: QuerySnapshot) {
        val authoritativeServerSnapshot = !snapshot.metadata.isFromCache
        val remoteRecords = linkedMapOf<String, SavingsRecord>()
        snapshot.documents.forEach { document ->
            recordFromDocument(document)?.let { remoteRecords[it.id] = it }
        }

        val localRecords = savingsRecords.associateBy { it.id }
        val merged = linkedMapOf<String, SavingsRecord>()

        remoteRecords.forEach { (id, record) ->
            if (pendingOperations[id]?.action != ACTION_DELETE) merged[id] = record
        }

        localRecords.forEach { (id, localRecord) ->
            when (pendingOperations[id]?.action) {
                ACTION_DELETE -> merged.remove(id)
                ACTION_UPSERT -> merged[id] = localRecord
                else -> {
                    if (remoteRecords.containsKey(id)) {
                        merged[id] = remoteRecords[id] ?: localRecord
                    } else if (!authoritativeServerSnapshot) {
                        merged[id] = localRecord
                    }
                }
            }
        }

        savingsRecords.clear()
        savingsRecords.addAll(merged.values.sortedByDescending { it.date })
        userId?.let { saveLocalState(it) }
    }

    private fun recordFromDocument(document: DocumentSnapshot): SavingsRecord? {
        val dateMillis = readDateMillis(document) ?: return null
        val id = document.getString("id")?.takeIf { it.isNotBlank() } ?: document.id
        val rawType = document.getString("type")
            ?: document.getString("transactionType")
            ?: document.getString("transaction_type")
            ?: "Contribution"
        val updatedAt = (document.get("localUpdatedAt") as? Number)?.toLong()
            ?: when (val updatedValue = document.get("updatedAt")) {
                is Timestamp -> updatedValue.toDate().time
                is Date -> updatedValue.time
                is Number -> updatedValue.toLong()
                else -> dateMillis
            }

        return SavingsRecord(
            id = id,
            type = normalizeTransactionType(rawType),
            account = document.getString("account")
                ?: document.getString("accountName")
                ?: document.getString("savingsAccount")
                ?: "",
            amount = documentAmount(document, "amount", "value", "transactionAmount"),
            date = dateMillis,
            source = document.getString("source")
                ?: document.getString("moneySource")
                ?: "",
            reference = document.getString("reference")
                ?: document.getString("referenceNumber")
                ?: "",
            notes = document.getString("notes") ?: "",
            updatedAt = updatedAt
        )
    }

    private fun readDateMillis(document: DocumentSnapshot): Long? {
        val fields = listOf("date", "transactionDate", "dateMillis", "timestamp", "dateString")
        for (field in fields) {
            when (val value = document.get(field)) {
                is Timestamp -> return value.toDate().time
                is Date -> return value.time
                is Number -> {
                    val raw = value.toLong()
                    return if (raw < 100_000_000_000L) raw * 1000L else raw
                }
                is String -> parseDate(value)?.let { return it.time }
            }
        }
        return null
    }

    private fun parseDate(value: String): Date? {
        val patterns = listOf("yyyy-MM-dd", "dd MMM yyyy", "MM/dd/yyyy", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = manilaTimeZone
                    isLenient = false
                }.parse(value)
            } catch (_: Exception) {
                // Try the next supported date format.
            }
        }
        return null
    }

    private fun documentAmount(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            parseAmount(document.get(field))?.let { return it }
        }
        return 0.0
    }

    private fun parseAmount(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim()
            .replace(",", "")
            .replace("₱", "")
            .replace("PHP", "", ignoreCase = true)
            .trim()
            .toDoubleOrNull()
        else -> null
    }

    private fun normalizeTransactionType(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return if (value.contains("withdraw") || value == "withdrawal" || value == "withdraw") {
            withdrawalType
        } else {
            contributionType
        }
    }

    // ==========================================
    // FIREBASE SYNC QUEUE
    // ==========================================

    private fun markPendingUpsert(record: SavingsRecord) {
        pendingOperations[record.id] = PendingOperation(
            id = record.id,
            action = ACTION_UPSERT,
            updatedAt = record.updatedAt
        )
        userId?.let { saveLocalState(it) }
    }

    private fun markPendingDelete(id: String) {
        val timestamp = System.currentTimeMillis()
        pendingOperations[id] = PendingOperation(id, ACTION_DELETE, timestamp)
        userId?.let { saveLocalState(it) }
    }

    private fun syncPendingOperations() {
        val collection = savingsCollection() ?: return
        if (pendingOperations.isEmpty()) {
            if (hasLoadedCloud) updateSyncStatus("Synced with Firebase")
            return
        }

        pendingOperations.values.toList().forEach { operation ->
            if (!operationsInFlight.add(operation.id)) return@forEach

            when (operation.action) {
                ACTION_UPSERT -> {
                    val record = savingsRecords.firstOrNull { it.id == operation.id }
                    if (record == null) {
                        operationsInFlight.remove(operation.id)
                        return@forEach
                    }

                    collection.document(record.id)
                        .set(recordToFirestoreMap(record), SetOptions.merge())
                        .addOnSuccessListener {
                            operationsInFlight.remove(operation.id)
                            removePendingIfUnchanged(operation)
                        }
                        .addOnFailureListener { exception ->
                            operationsInFlight.remove(operation.id)
                            updateSyncStatus("Saved locally · Firebase sync will retry")
                            android.util.Log.w("SavingsActivity", "Savings ${record.id} remains queued for Firebase sync", exception)
                        }
                }
                ACTION_DELETE -> {
                    collection.document(operation.id)
                        .delete()
                        .addOnSuccessListener {
                            operationsInFlight.remove(operation.id)
                            removePendingIfUnchanged(operation)
                        }
                        .addOnFailureListener { exception ->
                            operationsInFlight.remove(operation.id)
                            updateSyncStatus("Delete saved locally · Firebase sync will retry")
                            android.util.Log.w("SavingsActivity", "Savings delete ${operation.id} remains queued for Firebase sync", exception)
                        }
                }
            }
        }
        updateSyncStatus("Saved on this device · Syncing ${pendingOperations.size} change(s)…")
    }

    private fun recordToFirestoreMap(record: SavingsRecord): HashMap<String, Any> {
        return hashMapOf(
            "id" to record.id,
            "type" to record.type,
            "transactionType" to record.type,
            "account" to record.account,
            "accountName" to record.account,
            "amount" to record.amount,
            "date" to Timestamp(Date(record.date)),
            "transactionDate" to Timestamp(Date(record.date)),
            "dateMillis" to record.date,
            "dateString" to isoDateFormatter.format(Date(record.date)),
            "source" to record.source,
            "reference" to record.reference,
            "notes" to record.notes,
            "localUpdatedAt" to record.updatedAt,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    private fun removePendingIfUnchanged(completed: PendingOperation) {
        val current = pendingOperations[completed.id]
        if (current != null && current.action == completed.action && current.updatedAt == completed.updatedAt) {
            pendingOperations.remove(completed.id)
            userId?.let { saveLocalState(it) }
        }

        if (pendingOperations.isEmpty()) {
            updateSyncStatus("Synced with Firebase")
        } else {
            updateSyncStatus("Firebase sync pending · ${pendingOperations.size} change(s) left")
            syncPendingOperations()
        }
    }

    private fun updateSyncStatus(message: String) {
        if (!::tvSavingsSyncStatus.isInitialized) return
        tvSavingsSyncStatus.text = message
    }

    private fun showDataError(message: String) {
        val errorView = findViewById<TextView>(R.id.tvSavingsError)
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun clearDataError() {
        findViewById<View>(R.id.tvSavingsError).visibility = View.GONE
    }

    // ==========================================
    // UI HELPERS
    // ==========================================

    private fun makeText(
        value: String,
        size: Float,
        color: String,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {

            text = value

            textSize = size

            setTextColor(
                Color.parseColor(color)
            )

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }
    }

    private fun createAction(
        label: String,
        backgroundColor: String,
        textColor: String,
        action: () -> Unit
    ): TextView {

        return makeText(
            label,
            10f,
            textColor,
            true
        ).apply {

            gravity = Gravity.CENTER

            setPadding(
                dp(16),
                dp(9),
                dp(16),
                dp(9)
            )

            background = roundedBackground(
                backgroundColor
            )

            isClickable = true
            isFocusable = true

            setOnClickListener {
                action()
            }
        }
    }

    private fun actionLayoutParams(): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(8)
        }
    }

    private fun roundedBackground(
        color: String,
        strokeColor: String = color
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.parseColor(color)
            )

            cornerRadius = dp(11).toFloat()

            setStroke(
                dp(1),
                Color.parseColor(strokeColor)
            )
        }
    }

    // ==========================================
    // DATE AND CURRENCY HELPERS
    // ==========================================

    private fun getAmount(editText: EditText): Double {

        return editText.text.toString()
            .trim()
            .replace(",", "")
            .toDoubleOrNull() ?: 0.0
    }

    private fun formatMoney(amount: Double): String {

        return currencyFormatter.format(amount)
    }

    private fun formatDate(timestamp: Long): String {

        return dateFormatter.format(timestamp)
    }

    private fun dp(value: Int): Int {

        return (
                value * resources.displayMetrics.density
                ).toInt()
    }

    private fun showMessage(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }
}

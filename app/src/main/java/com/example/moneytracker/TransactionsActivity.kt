package com.example.moneytracker

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.io.OutputStreamWriter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TransactionsActivity : AppCompatActivity() {

    companion object {
        private const val SETTINGS_PREFS = "money_tracker_settings"
        private const val PAGE_SIZE = 20

        private const val TYPE_ALL = "All Transactions"
        private const val TYPE_SALARY = "Salary"
        private const val TYPE_EXPENSE = "Expense"
        private const val TYPE_CONTRIBUTION = "Savings Contribution"
        private const val TYPE_WITHDRAWAL = "Savings Withdrawal"
        private const val TYPE_BILL = "Bill Payment"
        private const val TYPE_LOAN = "Loan Payment"

        private const val CATEGORY_ALL = "All Categories"
        private const val PAYDAY_ALL = "All Paydays"
        private const val PAYDAY_15TH = "15th Salary (15th–End)"
        private const val PAYDAY_MONTH_END = "Month-end Salary (1st–14th)"

        private val FIRESTORE_COLLECTIONS = listOf(
            "salaries",
            "expenses",
            "savings",
            "payments",
            "loans",
            "obligations"
        )

        private val TRANSACTION_TYPES = listOf(
            TYPE_ALL,
            TYPE_SALARY,
            TYPE_EXPENSE,
            TYPE_CONTRIBUTION,
            TYPE_WITHDRAWAL,
            TYPE_BILL,
            TYPE_LOAN
        )

        private val PAYDAY_FILTERS = listOf(
            PAYDAY_ALL,
            PAYDAY_15TH,
            PAYDAY_MONTH_END
        )
    }

    private data class FinanceTransaction(
        val id: String,
        val type: String,
        val description: String,
        val amount: Double,
        val dateMillis: Long,
        val category: String,
        val method: String,
        val reference: String,
        val paydayPeriod: String,
        val notes: String,
        val source: String
    )

    private val manilaTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private val selectedMonth: Calendar = Calendar.getInstance(manilaTimeZone).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private val settingsPrefs: SharedPreferences by lazy {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
    }

    private val allTransactions = mutableListOf<FinanceTransaction>()
    private var monthTransactions: List<FinanceTransaction> = emptyList()
    private var categorySpinnerValues: List<String> = listOf(CATEGORY_ALL)
    private var visibleTransactionLimit = PAGE_SIZE
    private var recordsWithoutDate = 0
    private var isReady = false
    private var suppressFilterCallbacks = false
    private var loadGeneration = 0

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) exportTransactions(uri)
    }

    // ---------------------------------------------------------
    // LIFECYCLE
    // ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_transactions)

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

        setupToolbar()
        setupMonthNavigation()
        setupFilters()
        setupTransactionDetails()
        updateMonthTitle()
        setLoading(true)
        isReady = true
    }

    override fun onResume() {
        super.onResume()
        if (isReady) {
            loadTransactionsFromFirestore()
        }
    }

    // ---------------------------------------------------------
    // TOOLBAR
    // ---------------------------------------------------------

    private fun setupToolbar() {
        findViewById<TextView>(R.id.btnTransactionsBack).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnExportTransactions).setOnClickListener {
            val fileName = "my_finance_transactions_${
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            }.csv"
            exportLauncher.launch(fileName)
        }
    }

    // ---------------------------------------------------------
    // MONTH NAVIGATION
    // ---------------------------------------------------------

    private fun setupMonthNavigation() {
        findViewById<TextView>(R.id.btnPreviousTransactionMonth).setOnClickListener {
            selectedMonth.add(Calendar.MONTH, -1)
            selectedMonth.set(Calendar.DAY_OF_MONTH, 1)
            visibleTransactionLimit = PAGE_SIZE
            closeTransactionDetails()
            renderScreen()
        }

        findViewById<TextView>(R.id.btnNextTransactionMonth).setOnClickListener {
            selectedMonth.add(Calendar.MONTH, 1)
            selectedMonth.set(Calendar.DAY_OF_MONTH, 1)
            visibleTransactionLimit = PAGE_SIZE
            closeTransactionDetails()
            renderScreen()
        }
    }

    // ---------------------------------------------------------
    // FILTERS
    // ---------------------------------------------------------

    private fun setupFilters() {
        setSpinnerAdapter(
            R.id.spTransactionType,
            TRANSACTION_TYPES
        )
        setSpinnerAdapter(
            R.id.spTransactionCategory,
            listOf(CATEGORY_ALL)
        )
        setSpinnerAdapter(
            R.id.spTransactionPaydayPeriod,
            PAYDAY_FILTERS
        )

        findViewById<EditText>(R.id.etTransactionsSearch)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    visibleTransactionLimit = PAGE_SIZE
                    renderTransactionList()
                }
            })

        setupSpinnerListener(R.id.spTransactionType)
        setupSpinnerListener(R.id.spTransactionCategory)
        setupSpinnerListener(R.id.spTransactionPaydayPeriod)

        findViewById<TextView>(R.id.btnClearTransactionFilters).setOnClickListener {
            suppressFilterCallbacks = true
            findViewById<EditText>(R.id.etTransactionsSearch).setText("")
            findViewById<Spinner>(R.id.spTransactionType).setSelection(0)
            findViewById<Spinner>(R.id.spTransactionCategory).setSelection(0)
            findViewById<Spinner>(R.id.spTransactionPaydayPeriod).setSelection(0)
            suppressFilterCallbacks = false
            visibleTransactionLimit = PAGE_SIZE
            renderTransactionList()
        }

        findViewById<TextView>(R.id.btnLoadMoreTransactions).setOnClickListener {
            visibleTransactionLimit += PAGE_SIZE
            renderTransactionList()
        }
    }

    private fun setSpinnerAdapter(spinnerId: Int, values: List<String>) {
        val spinner = findViewById<Spinner>(spinnerId)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            values
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupSpinnerListener(spinnerId: Int) {
        findViewById<Spinner>(spinnerId).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (!suppressFilterCallbacks) {
                        visibleTransactionLimit = PAGE_SIZE
                        renderTransactionList()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    // ---------------------------------------------------------
    // FIRESTORE LOADING
    // ---------------------------------------------------------

    private fun loadTransactionsFromFirestore() {
        val user = auth.currentUser
        if (user == null) {
            allTransactions.clear()
            monthTransactions = emptyList()
            setLoading(false)
            renderScreen()
            showError("You are signed out. Please sign in again to view your transactions.")
            return
        }

        val generation = ++loadGeneration
        val userRef = db.collection("users").document(user.uid)
        val snapshots = mutableMapOf<String, QuerySnapshot>()
        val failures = mutableMapOf<String, String>()
        var completedRequests = 0

        setLoading(true)
        hideError()
        findViewById<TextView>(R.id.tvTransactionsStatus).visibility = View.GONE
        closeTransactionDetails()

        FIRESTORE_COLLECTIONS.forEach { collectionName ->
            userRef.collection(collectionName)
                .get()
                .addOnSuccessListener { result ->
                    if (generation != loadGeneration) return@addOnSuccessListener
                    snapshots[collectionName] = result
                    completedRequests++
                    if (completedRequests == FIRESTORE_COLLECTIONS.size) {
                        finishFirestoreLoad(generation, snapshots, failures)
                    }
                }
                .addOnFailureListener { exception ->
                    if (generation != loadGeneration) return@addOnFailureListener
                    failures[collectionName] = exception.localizedMessage
                        ?: exception.javaClass.simpleName
                    completedRequests++
                    if (completedRequests == FIRESTORE_COLLECTIONS.size) {
                        finishFirestoreLoad(generation, snapshots, failures)
                    }
                }
        }
    }

    private fun finishFirestoreLoad(
        generation: Int,
        snapshots: Map<String, QuerySnapshot>,
        failures: Map<String, String>
    ) {
        if (generation != loadGeneration) return

        allTransactions.clear()
        recordsWithoutDate = 0

        loadSalaryRecords(snapshots["salaries"])
        loadExpenseRecords(snapshots["expenses"])
        loadSavingsRecords(snapshots["savings"])
        loadPaymentRecords(snapshots["payments"])

        // The loans and obligations collections describe balances and unpaid items.
        // They are deliberately not added as cash transactions. Actual repayments
        // should be stored in the user's "payments" collection.

        allTransactions.sortByDescending { it.dateMillis }
        setLoading(false)
        renderScreen()

        val loadedCount = allTransactions.size
        val status = findViewById<TextView>(R.id.tvTransactionsStatus)

        if (failures.isNotEmpty()) {
            status.text = "Loaded $loadedCount transaction(s) from collections that were accessible."
            status.setTextColor(Color.parseColor("#D58A36"))
            status.visibility = View.VISIBLE

            val failureDetails = failures.entries.joinToString("\n") { (name, reason) ->
                "• $name: $reason"
            }
            showError(
                "Some records could not be loaded. Records from successful collections " +
                        "are still shown.\n$failureDetails"
            )
        } else if (recordsWithoutDate > 0) {
            status.text = "Loaded $loadedCount transaction(s). $recordsWithoutDate record(s) were skipped because their date could not be read."
            status.setTextColor(Color.parseColor("#D58A36"))
            status.visibility = View.VISIBLE
        } else {
            status.text = "Loaded $loadedCount transaction(s) from Firebase."
            status.setTextColor(Color.parseColor("#149B71"))
            status.visibility = View.VISIBLE
        }
    }

    private fun setLoading(isLoading: Boolean) {
        findViewById<LinearLayout>(R.id.transactionsLoading).visibility =
            if (isLoading) View.VISIBLE else View.GONE
        findViewById<ProgressBar>(R.id.transactionsProgress).visibility = View.GONE

        if (isLoading) {
            findViewById<LinearLayout>(R.id.transactionsEmptyState).visibility = View.GONE
            findViewById<LinearLayout>(R.id.transactionsListContainer).visibility = View.GONE
            findViewById<TextView>(R.id.btnLoadMoreTransactions).visibility = View.GONE
        }
    }

    // ---------------------------------------------------------
    // SALARY RECORDS
    // ---------------------------------------------------------

    private fun loadSalaryRecords(snapshot: QuerySnapshot?) {
        snapshot?.documents?.forEach { doc ->
            if (!isCompletedRecord(doc)) return@forEach

            val dateMillis = getRecordDate(doc)
            if (dateMillis == null) {
                recordsWithoutDate++
                return@forEach
            }

            val gross = amountFrom(doc, "gross", "grossPay", "basicSalary", "salary")
            val explicitNet = amountFrom(
                doc,
                "netSalary",
                "netAmount",
                "netPay",
                "takeHomePay",
                "amount"
            )

            val amount = if (explicitNet > 0.0) {
                explicitNet
            } else if (gross > 0.0) {
                (
                        gross + amountFrom(doc, "allowances", "allowance") +
                                amountFrom(doc, "overtime", "overtimePay") -
                                amountFrom(doc, "sss", "sssDeduction") -
                                amountFrom(doc, "philhealth", "philHealth", "philhealthDeduction") -
                                amountFrom(doc, "pagibig", "pagIbig", "pagibigDeduction") -
                                amountFrom(doc, "tax", "withholdingTax") -
                                amountFrom(doc, "otherDeductions", "other_deductions", "otherDeduction")
                        ).coerceAtLeast(0.0)
            } else {
                0.0
            }

            if (amount <= 0.0) return@forEach

            val savedPeriod = textFrom(doc, "paydayPeriod", "paydayPeriodLabel", "period", "payPeriod", "salaryPeriod")
            val description = textFrom(doc, "description", "salaryName", "period", "payPeriod")
                .ifBlank { "Salary received" }

            allTransactions.add(
                FinanceTransaction(
                    id = doc.id,
                    type = TYPE_SALARY,
                    description = description,
                    amount = amount,
                    dateMillis = dateMillis,
                    category = "Salary",
                    method = textFrom(doc, "method", "paymentMethod", "paymentMode").ifBlank { "—" },
                    reference = textFrom(doc, "reference", "referenceNumber", "transactionReference"),
                    paydayPeriod = getPaydayPeriod(dateMillis, savedPeriod),
                    notes = textFrom(doc, "notes", "remarks"),
                    source = "Salary Records"
                )
            )
        }
    }

    // ---------------------------------------------------------
    // EXPENSE RECORDS
    // ---------------------------------------------------------

    private fun loadExpenseRecords(snapshot: QuerySnapshot?) {
        snapshot?.documents?.forEach { doc ->
            if (!isCompletedRecord(doc)) return@forEach

            val dateMillis = getRecordDate(doc)
            if (dateMillis == null) {
                recordsWithoutDate++
                return@forEach
            }

            val amount = amountFrom(doc, "amount", "total", "totalAmount", "expenseAmount", "cost")
            if (amount <= 0.0) return@forEach

            val category = textFrom(doc, "category", "expenseCategory").ifBlank { "General" }
            val description = textFrom(doc, "description", "name", "title", "item", "merchant")
                .ifBlank { category }

            val savedPeriod = textFrom(doc, "paydayPeriod", "paydayPeriodLabel")

            allTransactions.add(
                FinanceTransaction(
                    id = doc.id,
                    type = TYPE_EXPENSE,
                    description = description,
                    amount = amount,
                    dateMillis = dateMillis,
                    category = category,
                    method = textFrom(doc, "paymentMethod", "method", "payment").ifBlank { "—" },
                    reference = textFrom(doc, "reference", "referenceNumber", "receiptNumber"),
                    paydayPeriod = getPaydayPeriod(dateMillis, savedPeriod),
                    notes = textFrom(doc, "notes", "remarks"),
                    source = "Expenses"
                )
            )
        }
    }

    // ---------------------------------------------------------
    // SAVINGS RECORDS
    // ---------------------------------------------------------

    private fun loadSavingsRecords(snapshot: QuerySnapshot?) {
        snapshot?.documents?.forEach { doc ->
            if (!isCompletedRecord(doc)) return@forEach

            val dateMillis = getRecordDate(doc)
            if (dateMillis == null) {
                recordsWithoutDate++
                return@forEach
            }

            val amount = amountFrom(doc, "amount", "transactionAmount", "totalAmount", "value")
            if (amount <= 0.0) return@forEach

            val transactionType = textFrom(doc, "transactionType", "type", "movementType")
                .lowercase(Locale.getDefault())
            val isWithdrawal = transactionType.contains("withdraw")
            val type = if (isWithdrawal) TYPE_WITHDRAWAL else TYPE_CONTRIBUTION

            val account = textFrom(doc, "accountName", "account", "name", "category")
            val description = textFrom(doc, "description", "source")
                .ifBlank { account.ifBlank { if (isWithdrawal) "Savings withdrawal" else "Savings contribution" } }

            allTransactions.add(
                FinanceTransaction(
                    id = doc.id,
                    type = type,
                    description = description,
                    amount = amount,
                    dateMillis = dateMillis,
                    category = account.ifBlank { "Savings" },
                    method = textFrom(doc, "method", "paymentMethod").ifBlank { "—" },
                    reference = textFrom(doc, "reference", "referenceNumber"),
                    paydayPeriod = getPaydayPeriod(dateMillis, textFrom(doc, "paydayPeriod", "paydayPeriodLabel")),
                    notes = textFrom(doc, "notes", "remarks"),
                    source = "Savings Records"
                )
            )
        }
    }

    // ---------------------------------------------------------
    // BILL AND LOAN PAYMENT RECORDS
    // ---------------------------------------------------------

    private fun loadPaymentRecords(snapshot: QuerySnapshot?) {
        snapshot?.documents?.forEach { doc ->
            if (!isCompletedRecord(doc)) return@forEach

            val dateMillis = getRecordDate(doc)
            if (dateMillis == null) {
                recordsWithoutDate++
                return@forEach
            }

            var amount = amountFrom(
                doc,
                "amount",
                "paymentAmount",
                "paidAmount",
                "totalPaid",
                "total"
            )

            if (amount <= 0.0) {
                amount = amountFrom(doc, "principal") +
                        amountFrom(doc, "interest") +
                        amountFrom(doc, "fees")
            }
            if (amount <= 0.0) return@forEach

            val description = textFrom(
                doc,
                "description",
                "payee",
                "obligationName",
                "loanName",
                "billName",
                "name",
                "title"
            )

            val classification = listOf(
                textFrom(doc, "type"),
                textFrom(doc, "paymentType"),
                textFrom(doc, "obligationType"),
                textFrom(doc, "kind"),
                textFrom(doc, "category"),
                textFrom(doc, "loanName"),
                textFrom(doc, "loanProvider"),
                textFrom(doc, "loanId"),
                textFrom(doc, "source"),
                description
            ).joinToString(" ").lowercase(Locale.getDefault())

            val isLoanFlag = doc.getBoolean("isLoan") == true ||
                    doc.getBoolean("loanPayment") == true
            val isLoan = isLoanFlag || classification.contains("loan") ||
                    classification.contains("shopeepay") ||
                    classification.contains("homecredit") ||
                    classification.contains("home credit")

            val type = if (isLoan) TYPE_LOAN else TYPE_BILL
            val finalDescription = description.ifBlank {
                if (isLoan) "Loan payment" else "Bill payment"
            }

            val breakdown = mutableListOf<String>()
            val principal = nullableAmountFrom(doc, "principal")
            val interest = nullableAmountFrom(doc, "interest")
            val fees = nullableAmountFrom(doc, "fees")
            principal?.let { breakdown.add("Principal: ${formatMoney(it)}") }
            interest?.let { breakdown.add("Interest: ${formatMoney(it)}") }
            fees?.let { breakdown.add("Fees: ${formatMoney(it)}") }

            val savedNotes = textFrom(doc, "notes", "remarks")
            val notes = listOf(savedNotes, breakdown.joinToString(" • "))
                .filter { it.isNotBlank() }
                .joinToString("\n")

            allTransactions.add(
                FinanceTransaction(
                    id = doc.id,
                    type = type,
                    description = finalDescription,
                    amount = amount,
                    dateMillis = dateMillis,
                    category = textFrom(doc, "category", "obligationType", "paymentType")
                        .ifBlank { if (isLoan) "Loan" else "Bill" },
                    method = textFrom(doc, "method", "paymentMethod", "paymentMode").ifBlank { "—" },
                    reference = textFrom(doc, "reference", "referenceNumber", "transactionReference"),
                    paydayPeriod = getPaydayPeriod(dateMillis, textFrom(doc, "paydayPeriod", "paydayPeriodLabel")),
                    notes = notes,
                    source = if (isLoan) "Loan Payment History" else "Bill Payment History"
                )
            )
        }
    }

    // ---------------------------------------------------------
    // FIRESTORE FIELD HELPERS
    // ---------------------------------------------------------

    private fun textFrom(document: DocumentSnapshot, vararg fields: String): String {
        for (field in fields) {
            val value = document.get(field) ?: continue
            val text = when (value) {
                is String -> value.trim()
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> ""
            }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun nullableAmountFrom(document: DocumentSnapshot, vararg fields: String): Double? {
        for (field in fields) {
            val value = document.get(field) ?: continue
            val parsed = parseAmount(value)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun amountFrom(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            val value = document.get(field) ?: continue
            val parsed = parseAmount(value) ?: continue
            if (parsed != 0.0) return parsed
        }
        return 0.0
    }

    private fun parseAmount(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val cleaned = value
                    .replace(",", "")
                    .replace("₱", "")
                    .replace("$", "")
                    .replace("PHP", "", ignoreCase = true)
                    .trim()
                cleaned.toDoubleOrNull()
            }
            else -> null
        }
    }

    private fun isCompletedRecord(document: DocumentSnapshot): Boolean {
        val status = textFrom(document, "status", "paymentStatus")
            .lowercase(Locale.getDefault())
        return status !in setOf(
            "pending",
            "unpaid",
            "cancelled",
            "canceled",
            "rejected",
            "void",
            "deleted",
            "failed"
        )
    }

    // ---------------------------------------------------------
    // DATE PARSING
    // ---------------------------------------------------------

    private fun getRecordDate(document: DocumentSnapshot): Long? {
        val dateFields = listOf(
            "date",
            "transactionDate",
            "expenseDate",
            "salaryDate",
            "paymentDate",
            "dateMillis",
            "timestamp",
            "createdAt",
            "dateString"
        )

        for (field in dateFields) {
            val value = document.get(field) ?: continue
            val parsed = parseDateValue(value)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseDateValue(value: Any?): Long? {
        return when (value) {
            is Timestamp -> value.toDate().time
            is Date -> value.time
            is Number -> {
                val number = value.toLong()
                when {
                    number >= 100_000_000_000L -> number
                    number in 1_000_000_000L until 100_000_000_000L -> number * 1000L
                    number in 19_000_101L..30_001_231L -> parseDateString(number.toString())
                    else -> null
                }
            }
            is String -> parseDateString(value)
            else -> null
        }
    }

    private fun parseDateString(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null

        val numeric = value.toLongOrNull()
        if (numeric != null) {
            when {
                numeric >= 100_000_000_000L -> return numeric
                numeric in 1_000_000_000L until 100_000_000_000L -> return numeric * 1000L
                value.length == 8 -> return parseWithPattern(value, "yyyyMMdd")
            }
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "dd-MM-yyyy",
            "MMM d, yyyy",
            "MMMM d, yyyy",
            "d MMM yyyy",
            "dd MMM yyyy"
        )

        patterns.forEach { pattern ->
            val parsed = parseWithPattern(value, pattern)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseWithPattern(value: String, pattern: String): Long? {
        return try {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = manilaTimeZone
            }.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------
    // PAYDAY PERIOD
    // ---------------------------------------------------------

    private fun getPaydayPeriod(dateMillis: Long, savedPeriod: String = ""): String {
        val normalized = savedPeriod.lowercase(Locale.getDefault())

        if (
            normalized.contains("month_end_salary") ||
            normalized.contains("month-end") ||
            normalized.contains("month end") ||
            normalized.contains("1st–14th") ||
            normalized.contains("1st-14th")
        ) return PAYDAY_MONTH_END

        if (
            normalized.contains("15th_salary") ||
            normalized.contains("15th") ||
            normalized.contains("15–end") ||
            normalized.contains("15-end")
        ) return PAYDAY_15TH

        val calendar = Calendar.getInstance(manilaTimeZone)
        calendar.timeInMillis = dateMillis
        return if (calendar.get(Calendar.DAY_OF_MONTH) >= 15) PAYDAY_15TH else PAYDAY_MONTH_END
    }

    // ---------------------------------------------------------
    // SCREEN RENDERING
    // ---------------------------------------------------------

    private fun renderScreen() {
        updateMonthTitle()
        monthTransactions = allTransactions.filter { isInSelectedMonth(it.dateMillis) }
        updateMonthlySummary()
        updateCategoryFilter()
        renderTransactionList()
        updateDataStatus()
    }

    private fun updateMonthTitle() {
        findViewById<TextView>(R.id.tvTransactionsMonth).text =
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).apply {
                timeZone = manilaTimeZone
            }.format(selectedMonth.time)
    }

    private fun isInSelectedMonth(dateMillis: Long): Boolean {
        if (dateMillis <= 0L) return false
        val transactionDate = Calendar.getInstance(manilaTimeZone).apply {
            timeInMillis = dateMillis
        }
        return transactionDate.get(Calendar.YEAR) == selectedMonth.get(Calendar.YEAR) &&
                transactionDate.get(Calendar.MONTH) == selectedMonth.get(Calendar.MONTH)
    }

    // ---------------------------------------------------------
    // MONTHLY SUMMARY
    // ---------------------------------------------------------

    private fun updateMonthlySummary() {
        val salary = monthTransactions.filter { it.type == TYPE_SALARY }.sumOf { it.amount }
        val expenses = monthTransactions.filter { it.type == TYPE_EXPENSE }.sumOf { it.amount }
        val contributions = monthTransactions.filter { it.type == TYPE_CONTRIBUTION }.sumOf { it.amount }
        val withdrawals = monthTransactions.filter { it.type == TYPE_WITHDRAWAL }.sumOf { it.amount }
        val billPayments = monthTransactions.filter { it.type == TYPE_BILL }.sumOf { it.amount }
        val loanPayments = monthTransactions.filter { it.type == TYPE_LOAN }.sumOf { it.amount }

        // Savings movements are transfers between cash and savings, not earned income.
        // This cash-flow view includes withdrawals as cash received and contributions as cash used.
        val moneyIn = salary + withdrawals
        val moneyOut = expenses + contributions + billPayments + loanPayments
        val netMovement = moneyIn - moneyOut

        findViewById<TextView>(R.id.tvTransactionsNetMovement).text = formatSignedMoney(netMovement)
        findViewById<TextView>(R.id.tvTransactionsNetDescription).text = if (netMovement >= 0.0) {
            "More money came in than went out during this month."
        } else {
            "More money went out than came in during this month."
        }
        findViewById<TextView>(R.id.tvTransactionsMoneyIn).text = formatMoney(moneyIn)
        findViewById<TextView>(R.id.tvTransactionsMoneyOut).text = formatMoney(moneyOut)
        findViewById<TextView>(R.id.tvTransactionsIncome).text = formatMoney(salary)
        findViewById<TextView>(R.id.tvTransactionsExpenses).text = formatMoney(expenses)
        findViewById<TextView>(R.id.tvTransactionsSavingsAdded).text = formatMoney(contributions)
        findViewById<TextView>(R.id.tvTransactionsPayments).text = formatMoney(billPayments + loanPayments)
        findViewById<TextView>(R.id.tvTransactionsSalaryTotal).text = formatMoney(salary)
        findViewById<TextView>(R.id.tvTransactionsExpenseTotal).text = formatMoney(expenses)
        findViewById<TextView>(R.id.tvTransactionsContributionTotal).text = formatMoney(contributions)
        findViewById<TextView>(R.id.tvTransactionsWithdrawalTotal).text = formatMoney(withdrawals)
        findViewById<TextView>(R.id.tvTransactionsBillPayments).text = formatMoney(billPayments)
        findViewById<TextView>(R.id.tvTransactionsLoanPayments).text = formatMoney(loanPayments)
    }

    // ---------------------------------------------------------
    // CATEGORY FILTER
    // ---------------------------------------------------------

    private fun updateCategoryFilter() {
        val categories = monthTransactions
            .map { it.category.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.getDefault()) }
        val newValues = listOf(CATEGORY_ALL) + categories
        if (newValues == categorySpinnerValues) return

        val spinner = findViewById<Spinner>(R.id.spTransactionCategory)
        val previousSelection = spinner.selectedItem?.toString() ?: CATEGORY_ALL
        categorySpinnerValues = newValues
        setSpinnerAdapter(R.id.spTransactionCategory, newValues)

        val selectedIndex = newValues.indexOf(previousSelection).coerceAtLeast(0)
        suppressFilterCallbacks = true
        spinner.setSelection(selectedIndex, false)
        suppressFilterCallbacks = false
    }

    // ---------------------------------------------------------
    // FILTERING
    // ---------------------------------------------------------

    private fun getFilteredTransactions(): List<FinanceTransaction> {
        val typeFilter = findViewById<Spinner>(R.id.spTransactionType).selectedItem?.toString() ?: TYPE_ALL
        val categoryFilter = findViewById<Spinner>(R.id.spTransactionCategory).selectedItem?.toString() ?: CATEGORY_ALL
        val paydayFilter = findViewById<Spinner>(R.id.spTransactionPaydayPeriod).selectedItem?.toString() ?: PAYDAY_ALL
        val search = findViewById<EditText>(R.id.etTransactionsSearch)
            .text.toString().trim().lowercase(Locale.getDefault())

        return monthTransactions.filter { transaction ->
            val matchesType = typeFilter == TYPE_ALL || transaction.type.equals(typeFilter, ignoreCase = true)
            val matchesCategory = categoryFilter == CATEGORY_ALL || transaction.category.equals(categoryFilter, ignoreCase = true)
            val matchesPayday = paydayFilter == PAYDAY_ALL || transaction.paydayPeriod == paydayFilter
            val searchable = listOf(
                transaction.description,
                transaction.category,
                transaction.reference,
                transaction.method,
                transaction.type,
                transaction.notes,
                transaction.source
            ).joinToString(" ").lowercase(Locale.getDefault())
            val matchesSearch = search.isBlank() || searchable.contains(search)
            matchesType && matchesCategory && matchesPayday && matchesSearch
        }.sortedByDescending { it.dateMillis }
    }

    // ---------------------------------------------------------
    // TRANSACTION LIST
    // ---------------------------------------------------------

    private fun renderTransactionList() {
        val listContainer = findViewById<LinearLayout>(R.id.transactionsListContainer)
        val emptyState = findViewById<LinearLayout>(R.id.transactionsEmptyState)
        val loadingState = findViewById<LinearLayout>(R.id.transactionsLoading)
        val loadMoreButton = findViewById<TextView>(R.id.btnLoadMoreTransactions)

        val records = getFilteredTransactions()
        listContainer.removeAllViews()
        loadingState.visibility = View.GONE
        findViewById<TextView>(R.id.tvTransactionCount).text = "${records.size} records"

        val typeFilter = findViewById<Spinner>(R.id.spTransactionType).selectedItem?.toString() ?: TYPE_ALL
        val categoryFilter = findViewById<Spinner>(R.id.spTransactionCategory).selectedItem?.toString() ?: CATEGORY_ALL
        val paydayFilter = findViewById<Spinner>(R.id.spTransactionPaydayPeriod).selectedItem?.toString() ?: PAYDAY_ALL
        val hasSearch = findViewById<EditText>(R.id.etTransactionsSearch).text.toString().isNotBlank()

        val activeFilters = mutableListOf<String>()
        if (typeFilter != TYPE_ALL) activeFilters.add(typeFilter)
        if (categoryFilter != CATEGORY_ALL) activeFilters.add(categoryFilter)
        if (paydayFilter != PAYDAY_ALL) activeFilters.add(paydayFilter)
        if (hasSearch) activeFilters.add("Search")

        findViewById<TextView>(R.id.tvTransactionFilterSummary).text =
            if (activeFilters.isEmpty()) {
                "Showing all transactions for the selected month."
            } else {
                "Filters: ${activeFilters.joinToString(" • ")}"
            }

        if (records.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            loadMoreButton.visibility = View.GONE
            return
        }

        emptyState.visibility = View.GONE
        listContainer.visibility = View.VISIBLE

        val visibleRecords = records.take(visibleTransactionLimit)
        visibleRecords.forEachIndexed { index, transaction ->
            listContainer.addView(createTransactionRow(transaction))
            if (index < visibleRecords.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(Color.parseColor("#EAECF2"))
                }
                listContainer.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        topMargin = dp(7)
                        bottomMargin = dp(7)
                    }
                )
            }
        }

        if (records.size > visibleTransactionLimit) {
            loadMoreButton.visibility = View.VISIBLE
            loadMoreButton.text = "Load More Transactions (${records.size - visibleTransactionLimit} remaining)"
        } else {
            loadMoreButton.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------
    // TRANSACTION ROW
    // ---------------------------------------------------------

    private fun createTransactionRow(transaction: FinanceTransaction): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(12), dp(11), dp(12))
            background = roundedBackground("#F8F8FC", 11)
            isClickable = true
            isFocusable = true
            contentDescription = "${transaction.type}: ${transaction.description}, ${formatMoney(transaction.amount)}"
            setOnClickListener { showTransactionDetails(transaction) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val description = TextView(this).apply {
            text = transaction.description
            textSize = 13f
            setTextColor(Color.parseColor("#202334"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val amount = TextView(this).apply {
            text = transactionAmountLabel(transaction)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(transactionColor(transaction.type))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(9) }
        }

        topRow.addView(description)
        topRow.addView(amount)

        val typeLabel = TextView(this).apply {
            text = transaction.type.uppercase(Locale.getDefault())
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(transactionColor(transaction.type))
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedBackground(transactionBackgroundColor(transaction.type), 6)
        }

        val subtitle = TextView(this).apply {
            text = "${transaction.category}  •  ${formatDate(transaction.dateMillis)}"
            textSize = 10f
            setTextColor(Color.parseColor("#858B9E"))
            maxLines = 2
        }

        val method = TextView(this).apply {
            text = if (transaction.method == "—") transaction.source else "${transaction.method}  •  ${transaction.source}"
            textSize = 9f
            setTextColor(Color.parseColor("#858B9E"))
            setPadding(0, dp(5), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        row.addView(topRow)
        row.addView(
            typeLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        row.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }
        )
        row.addView(method)
        return row
    }

    private fun transactionAmountLabel(transaction: FinanceTransaction): String {
        val prefix = when (transaction.type) {
            TYPE_SALARY, TYPE_WITHDRAWAL -> "+"
            TYPE_EXPENSE, TYPE_CONTRIBUTION, TYPE_BILL, TYPE_LOAN -> "−"
            else -> ""
        }
        return prefix + formatMoney(transaction.amount)
    }

    private fun transactionColor(type: String): Int {
        return when (type) {
            TYPE_SALARY, TYPE_WITHDRAWAL -> Color.parseColor("#149B71")
            TYPE_EXPENSE -> Color.parseColor("#D85D68")
            TYPE_CONTRIBUTION -> Color.parseColor("#6558D3")
            TYPE_BILL, TYPE_LOAN -> Color.parseColor("#D58A36")
            else -> Color.parseColor("#50556B")
        }
    }

    private fun transactionBackgroundColor(type: String): String {
        return when (type) {
            TYPE_SALARY, TYPE_WITHDRAWAL -> "#E7F7F0"
            TYPE_EXPENSE -> "#FFF1F1"
            TYPE_CONTRIBUTION -> "#EFEDFF"
            TYPE_BILL, TYPE_LOAN -> "#FFF5E9"
            else -> "#F0F1F5"
        }
    }

    // ---------------------------------------------------------
    // TRANSACTION DETAILS
    // ---------------------------------------------------------

    private fun setupTransactionDetails() {
        findViewById<TextView>(R.id.btnCloseTransactionDetails).setOnClickListener {
            closeTransactionDetails()
        }
    }

    private fun showTransactionDetails(transaction: FinanceTransaction) {
        findViewById<LinearLayout>(R.id.transactionDetailsCard).visibility = View.VISIBLE

        findViewById<TextView>(R.id.tvTransactionDetailType).apply {
            text = transaction.type
            setTextColor(transactionColor(transaction.type))
            background = roundedBackground(transactionBackgroundColor(transaction.type), 7)
        }
        findViewById<TextView>(R.id.tvTransactionDetailDescription).text = transaction.description
        findViewById<TextView>(R.id.tvTransactionDetailAmount).apply {
            text = transactionAmountLabel(transaction)
            setTextColor(transactionColor(transaction.type))
        }
        findViewById<TextView>(R.id.tvTransactionDetailDate).text = formatDate(transaction.dateMillis)
        findViewById<TextView>(R.id.tvTransactionDetailCategory).text = transaction.category
        findViewById<TextView>(R.id.tvTransactionDetailMethod).text = transaction.method
        findViewById<TextView>(R.id.tvTransactionDetailPayday).text = transaction.paydayPeriod
        findViewById<TextView>(R.id.tvTransactionDetailReference).text = transaction.reference.ifBlank { "—" }
        findViewById<TextView>(R.id.tvTransactionDetailNotes).text = transaction.notes.ifBlank { "No additional notes." }
        findViewById<TextView>(R.id.tvTransactionDetailSource).text = "Source: ${transaction.source}"

        val scrollView = findViewById<ScrollView>(R.id.transactionsScroll)
        val detailsCard = findViewById<LinearLayout>(R.id.transactionDetailsCard)
        scrollView.post {
            scrollView.smoothScrollTo(0, detailsCard.top)
        }
    }

    private fun closeTransactionDetails() {
        findViewById<LinearLayout>(R.id.transactionDetailsCard).visibility = View.GONE
    }

    // ---------------------------------------------------------
    // CSV EXPORT
    // ---------------------------------------------------------

    private fun exportTransactions(uri: Uri) {
        try {
            val records = getFilteredTransactions()
            val csv = buildString {
                append("Date,Type,Description,Category,Amount,Payment Method,Payday Period,Reference,Notes,Source\n")
                records.forEach { transaction ->
                    val values = listOf(
                        formatDate(transaction.dateMillis),
                        transaction.type,
                        transaction.description,
                        transaction.category,
                        transaction.amount.toString(),
                        transaction.method,
                        transaction.paydayPeriod,
                        transaction.reference,
                        transaction.notes,
                        transaction.source
                    )
                    append(values.joinToString(",") { csvEscape(it) })
                    append("\n")
                }
            }

            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Unable to open the selected file.")
            output.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(csv)
                    writer.flush()
                }
            }
            showStatus("Exported ${records.size} transaction record(s).")
        } catch (exception: Exception) {
            showError(exception.localizedMessage ?: "Unable to export transaction records.")
        }
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    // ---------------------------------------------------------
    // CURRENCY AND DATE FORMATTING
    // ---------------------------------------------------------

    private fun formatMoney(amount: Double): String {
        val currencySetting = settingsPrefs.getString("currency", "PHP (₱)") ?: "PHP (₱)"
        val currencyCode = when {
            currencySetting.contains("USD", true) -> "USD"
            currencySetting.contains("EUR", true) -> "EUR"
            currencySetting.contains("GBP", true) -> "GBP"
            currencySetting.contains("JPY", true) -> "JPY"
            currencySetting.contains("SGD", true) -> "SGD"
            else -> "PHP"
        }
        val symbol = when (currencyCode) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            "SGD" -> "S$"
            else -> "₱"
        }
        val pattern = if (currencyCode == "JPY") "#,##0" else "#,##0.00"
        val formatter = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        return symbol + formatter.format(kotlin.math.abs(amount))
    }

    private fun formatSignedMoney(amount: Double): String {
        val prefix = if (amount < 0.0) "−" else "+"
        return prefix + formatMoney(amount)
    }

    private fun formatDate(dateMillis: Long): String {
        if (dateMillis <= 0L) return "Date not available"
        val pattern = settingsPrefs.getString("date_format", "MMM d, yyyy") ?: "MMM d, yyyy"
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).apply {
                timeZone = manilaTimeZone
            }.format(Date(dateMillis))
        } catch (_: Exception) {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply {
                timeZone = manilaTimeZone
            }.format(Date(dateMillis))
        }
    }

    // ---------------------------------------------------------
    // STATUS / ERROR UI
    // ---------------------------------------------------------

    private fun updateDataStatus() {
        val status = findViewById<TextView>(R.id.tvTransactionsStatus)
        if (recordsWithoutDate > 0) {
            status.text = "$recordsWithoutDate record(s) were skipped because their date could not be read."
            status.setTextColor(Color.parseColor("#D58A36"))
            status.visibility = View.VISIBLE
        } else if (status.text.toString().contains("skipped", ignoreCase = true)) {
            status.visibility = View.GONE
        }
    }

    private fun showStatus(message: String) {
        val status = findViewById<TextView>(R.id.tvTransactionsStatus)
        status.text = message
        status.setTextColor(Color.parseColor("#149B71"))
        status.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        val error = findViewById<TextView>(R.id.tvTransactionsError)
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun hideError() {
        findViewById<TextView>(R.id.tvTransactionsError).visibility = View.GONE
    }

    // ---------------------------------------------------------
    // UI HELPERS
    // ---------------------------------------------------------

    private fun roundedBackground(color: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(color))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

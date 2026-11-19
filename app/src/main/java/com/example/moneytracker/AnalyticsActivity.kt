package com.example.moneytracker

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

class AnalyticsActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // Firebase and local cache
    // -------------------------------------------------------------------------

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val timezone: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    private val cacheVersion = 1
    private var lastSuccessfulSync = 0L
    private var loadGeneration = 0
    private var isLoading = false
    private var firstResume = true
    private var activeCacheUid: String? = null

    private val collectionNames = listOf(
        "salaries",
        "expenses",
        "savings",
        "payments",
        "loans",
        "obligations",
        "savingsGoals"
    )

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private lateinit var tvFromDate: TextView
    private lateinit var tvToDate: TextView
    private lateinit var tvFilterStatus: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvTotalExpenses: TextView
    private lateinit var tvNetCashFlow: TextView
    private lateinit var tvSavingsRate: TextView
    private lateinit var spCategory: Spinner
    private lateinit var spType: Spinner
    private lateinit var btnApplyFilters: View
    private lateinit var btnExportAnalytics: View
    private lateinit var btnAnalyticsBack: View

    private val purple = Color.rgb(101, 88, 211)
    private val green = Color.rgb(20, 155, 113)
    private val red = Color.rgb(216, 93, 104)
    private val orange = Color.rgb(213, 138, 54)
    private val darkText = Color.rgb(32, 35, 52)
    private val mutedText = Color.rgb(133, 139, 158)

    // -------------------------------------------------------------------------
    // Normalized analytics models
    // -------------------------------------------------------------------------

    private data class AnalyticsRecord(
        val id: String,
        val source: String,
        val type: String,
        val category: String,
        val description: String,
        val amount: Double,
        val date: Date,
        val paydayPeriod: String,
        val paymentMethod: String,
        val reference: String,
        val principal: Double = 0.0,
        val interest: Double = 0.0,
        val fees: Double = 0.0
    )

    private data class MonthlySummary(
        var income: Double = 0.0,
        var expenses: Double = 0.0,
        var payments: Double = 0.0,
        var contributions: Double = 0.0,
        var withdrawals: Double = 0.0
    )

    private data class DebtSummary(
        val principal: Double = 0.0,
        val repaid: Double = 0.0,
        val outstanding: Double = 0.0
    )

    private data class GoalSummary(
        val target: Double = 0.0,
        val saved: Double = 0.0,
        val completed: Int = 0,
        val active: Int = 0
    )

    private val records = mutableListOf<AnalyticsRecord>()
    private val selectedRecords = mutableListOf<AnalyticsRecord>()
    private var debtSummary = DebtSummary()
    private var goalSummary = GoalSummary()

    private var fromDate: Date = Date()
    private var toDate: Date = Date()
    private var savedCsvContent = ""

    private val categoryBaseOptions = listOf(
        "All Categories", "Salary", "Food & Dining", "Transportation",
        "Groceries", "Internet & Mobile", "Shopping", "Bills", "Loan",
        "Savings", "Other"
    )

    private val typeOptions = listOf(
        "All Types", "Income", "Expense", "Savings Contribution",
        "Savings Withdrawal", "Bill Payment", "Loan Payment"
    )

    // -------------------------------------------------------------------------
    // CSV document picker
    // -------------------------------------------------------------------------

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) {
            showToast("Export cancelled.")
        } else {
            writeCsvFile(uri)
        }
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_analytics)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        setupSpinners()
        initializeDateRange()
        setupClickListeners()
        updateDateLabels()
        checkUserAndLoadData()
    }

    override fun onResume() {
        super.onResume()
        // onCreate performs the first load. Reload when returning from another screen.
        if (firstResume) {
            firstResume = false
            return
        }
        if (!::auth.isInitialized) return
        val user = auth.currentUser
        if (user == null) {
            records.clear()
            selectedRecords.clear()
            openLogin()
        } else if (!user.isEmailVerified) {
            records.clear()
            selectedRecords.clear()
            openEmailVerification()
        } else {
            loadAnalyticsData()
        }
    }

    // -------------------------------------------------------------------------
    // Initialize views and layout behavior
    // -------------------------------------------------------------------------

    private fun initializeViews() {
        tvFromDate = findViewById(R.id.tvAnalyticsFromDate)
        tvToDate = findViewById(R.id.tvAnalyticsToDate)
        spCategory = findViewById(R.id.spAnalyticsCategory)
        spType = findViewById(R.id.spAnalyticsType)
        tvTotalIncome = findViewById(R.id.tvAnalyticsTotalIncome)
        tvTotalExpenses = findViewById(R.id.tvAnalyticsTotalExpenses)
        tvNetCashFlow = findViewById(R.id.tvAnalyticsNetCashFlow)
        tvSavingsRate = findViewById(R.id.tvAnalyticsSavingsRate)
        tvFilterStatus = findViewById(R.id.tvAnalyticsFilterStatus)
        btnAnalyticsBack = findViewById(R.id.btnAnalyticsBack)
        btnExportAnalytics = findViewById(R.id.btnExportAnalytics)
        btnApplyFilters = findViewById(R.id.btnApplyAnalyticsFilters)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupSpinners() {
        setSpinnerItems(spCategory, categoryBaseOptions)
        setSpinnerItems(spType, typeOptions)
    }

    private fun setSpinnerItems(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun refreshCategorySpinner() {
        val previousSelection = spCategory.selectedItem?.toString().orEmpty()
        val discovered = records.map { it.category.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.ROOT) }
        val options = (listOf("All Categories") + discovered).distinct()
        setSpinnerItems(spCategory, options)
        val selectedIndex = options.indexOfFirst {
            it.equals(previousSelection, ignoreCase = true)
        }
        spCategory.setSelection(if (selectedIndex >= 0) selectedIndex else 0)
    }

    // -------------------------------------------------------------------------
    // Window and click listeners
    // -------------------------------------------------------------------------

    private fun setupClickListeners() {
        btnAnalyticsBack.setOnClickListener { finish() }
        tvFromDate.setOnClickListener { showDatePicker(true) }
        tvToDate.setOnClickListener { showDatePicker(false) }
        btnApplyFilters.setOnClickListener { applyAnalyticsFilters() }
        btnExportAnalytics.setOnClickListener { exportAnalytics() }

        findViewById<View>(R.id.btnFilterThisMonth).setOnClickListener {
            selectThisMonth()
        }
        findViewById<View>(R.id.btnFilterLastMonth).setOnClickListener {
            selectLastMonth()
        }
        findViewById<View>(R.id.btnFilterThreeMonths).setOnClickListener {
            selectLastThreeMonths()
        }
        findViewById<View>(R.id.btnFilterThisYear).setOnClickListener {
            selectThisYear()
        }
    }

    // -------------------------------------------------------------------------
    // Date range and presets (Asia/Manila)
    // -------------------------------------------------------------------------

    private fun initializeDateRange() {
        val calendar = Calendar.getInstance(timezone)
        calendar.time = Date()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        fromDate = calendar.time
        toDate = endOfDay(Date())
    }

    private fun showDatePicker(isStartDate: Boolean) {
        val selected = if (isStartDate) fromDate else toDate
        val calendar = Calendar.getInstance(timezone).apply { time = selected }

        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance(timezone)
                picked.set(year, month, day, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                if (isStartDate) {
                    fromDate = picked.time
                    if (fromDate.after(toDate)) toDate = endOfDay(fromDate)
                } else {
                    toDate = endOfDay(picked.time)
                    if (toDate.before(fromDate)) fromDate = startOfDay(toDate)
                }
                updateDateLabels()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun selectThisMonth() {
        val calendar = Calendar.getInstance(timezone)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        fromDate = calendar.time
        toDate = endOfDay(Date())
        updateDateLabels()
        applyAnalyticsFilters()
    }

    private fun selectLastMonth() {
        val calendar = Calendar.getInstance(timezone)
        calendar.time = Date()
        calendar.add(Calendar.MONTH, -1)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        fromDate = calendar.time
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        toDate = endOfDay(calendar.time)
        updateDateLabels()
        applyAnalyticsFilters()
    }

    private fun selectLastThreeMonths() {
        val calendar = Calendar.getInstance(timezone)
        calendar.time = Date()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.MONTH, -2)
        fromDate = calendar.time
        toDate = endOfDay(Date())
        updateDateLabels()
        applyAnalyticsFilters()
    }

    private fun selectThisYear() {
        val calendar = Calendar.getInstance(timezone)
        calendar.time = Date()
        calendar.set(Calendar.MONTH, Calendar.JANUARY)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        fromDate = calendar.time
        toDate = endOfDay(Date())
        updateDateLabels()
        applyAnalyticsFilters()
    }

    private fun startOfDay(date: Date): Date {
        val calendar = Calendar.getInstance(timezone).apply { time = date }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }

    private fun endOfDay(date: Date): Date {
        val calendar = Calendar.getInstance(timezone).apply { time = date }
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.time
    }

    private fun updateDateLabels() {
        val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
            timeZone = timezone
        }
        tvFromDate.text = formatter.format(fromDate)
        tvToDate.text = formatter.format(toDate)
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    private fun checkUserAndLoadData() {
        val user = auth.currentUser
        if (user == null) {
            openLogin()
            return
        }
        if (!user.isEmailVerified) {
            openEmailVerification()
            return
        }
        loadAnalyticsData()
    }

    private fun openLogin() {
        val intent = Intent(this, Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun openEmailVerification() {
        val intent = Intent(this, EmailVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // -------------------------------------------------------------------------
    // Local-first load, then Firebase refresh
    // -------------------------------------------------------------------------

    private fun loadAnalyticsData() {
        val user = auth.currentUser ?: return
        val requestId = ++loadGeneration
        if (activeCacheUid != user.uid) {
            // Never allow one account's in-memory analytics to appear for another account.
            activeCacheUid = user.uid
            records.clear()
            selectedRecords.clear()
            debtSummary = DebtSummary()
            goalSummary = GoalSummary()
            lastSuccessfulSync = 0L
        }

        val loadedFromLocal = loadAnalyticsCache(user.uid)
        if (loadedFromLocal) {
            refreshCategorySpinner()
            applyAnalyticsFilters()
            renderDebtProgress()
            renderGoalsProgress()
            updateSavingsBalance()
            updateSyncStatus(
                if (lastSuccessfulSync > 0L) {
                    "Showing saved data • Checking for updates"
                } else {
                    "Showing saved data • Connecting to cloud"
                }
            )
            updateLastUpdatedLabel()
        } else {
            updateSyncStatus("Connecting to your financial data…")
        }

        setLoading(true)
        val userRef = db.collection("users").document(user.uid)
        val snapshots = mutableMapOf<String, QuerySnapshot>()
        val failedCollections = mutableSetOf<String>()
        var completed = 0

        collectionNames.forEach { collection ->
            userRef.collection(collection).get()
                .addOnSuccessListener { snapshot ->
                    if (requestId != loadGeneration || isFinishing || isDestroyed) return@addOnSuccessListener
                    snapshots[collection] = snapshot
                    completed++
                    finishLoadIfReady(requestId, user.uid, snapshots, failedCollections, completed)
                }
                .addOnFailureListener { exception ->
                    if (requestId != loadGeneration || isFinishing || isDestroyed) return@addOnFailureListener
                    failedCollections.add(collection)
                    android.util.Log.w(
                        "AnalyticsActivity",
                        "Unable to load $collection; keeping the local copy.",
                        exception
                    )
                    completed++
                    finishLoadIfReady(requestId, user.uid, snapshots, failedCollections, completed)
                }
        }
    }

    private fun finishLoadIfReady(
        requestId: Int,
        uid: String,
        snapshots: Map<String, QuerySnapshot>,
        failedCollections: Set<String>,
        completed: Int
    ) {
        if (requestId != loadGeneration || completed != collectionNames.size) return

        // Replace only the collections that loaded successfully. A failed collection
        // retains its last local version instead of disappearing from the screen.
        val refreshedRecords = buildAnalyticsRecords(snapshots)
        snapshots.keys.forEach { successfulCollection ->
            records.removeAll { it.source == successfulCollection }
            records.addAll(refreshedRecords.filter { it.source == successfulCollection })
        }

        if (snapshots.containsKey("loans")) {
            debtSummary = calculateDebtSummary(snapshots["loans"]?.documents.orEmpty())
        }
        if (snapshots.containsKey("savingsGoals")) {
            goalSummary = calculateGoalSummary(snapshots["savingsGoals"]?.documents.orEmpty())
        }

        records.sortByDescending { it.date }
        val receivedFreshServerData = failedCollections.isEmpty() &&
                snapshots.values.all { snapshot -> !snapshot.metadata.isFromCache }
        if (receivedFreshServerData) lastSuccessfulSync = System.currentTimeMillis()
        saveAnalyticsCache(uid)

        refreshCategorySpinner()
        setLoading(false)
        applyAnalyticsFilters()
        renderDebtProgress()
        renderGoalsProgress()
        updateSavingsBalance()
        updateLastUpdatedLabel()

        if (failedCollections.isEmpty() && receivedFreshServerData) {
            updateSyncStatus("All data is up to date • Saved on this device")
            updateOptionalText("tvAnalyticsError", "")
        } else if (failedCollections.isEmpty()) {
            updateSyncStatus("Showing Firebase's cached data • Waiting for a cloud connection")
            updateOptionalText("tvAnalyticsError", "")
        } else {
            updateSyncStatus("Offline or partially connected • Showing available saved data")
            updateOptionalText(
                "tvAnalyticsError",
                "${failedCollections.size} collection(s) could not be refreshed: ${failedCollections.joinToString()}. Your saved analytics remain available."
            )
        }
    }

    // -------------------------------------------------------------------------
    // Per-user SharedPreferences cache
    // -------------------------------------------------------------------------

    private fun cachePreferences(uid: String) =
        getSharedPreferences("analytics_cache_$uid", MODE_PRIVATE)

    private fun loadAnalyticsCache(uid: String): Boolean {
        val prefs = cachePreferences(uid)
        val rawRecords = prefs.getString("records", null) ?: return false
        if (prefs.getInt("cache_version", 0) > cacheVersion) return false

        return try {
            val array = JSONArray(rawRecords)
            records.clear()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val timestamp = item.optLong("date", 0L)
                if (timestamp <= 0L) continue
                records.add(
                    AnalyticsRecord(
                        id = item.optString("id"),
                        source = item.optString("source"),
                        type = item.optString("type"),
                        category = item.optString("category", "Other"),
                        description = item.optString("description"),
                        amount = item.optDouble("amount", 0.0),
                        date = Date(timestamp),
                        paydayPeriod = item.optString("paydayPeriod"),
                        paymentMethod = item.optString("paymentMethod"),
                        reference = item.optString("reference"),
                        principal = item.optDouble("principal", 0.0),
                        interest = item.optDouble("interest", 0.0),
                        fees = item.optDouble("fees", 0.0)
                    )
                )
            }

            val debt = JSONObject(prefs.getString("debt_summary", "{}") ?: "{}")
            debtSummary = DebtSummary(
                principal = debt.optDouble("principal", 0.0),
                repaid = debt.optDouble("repaid", 0.0),
                outstanding = debt.optDouble("outstanding", 0.0)
            )

            val goals = JSONObject(prefs.getString("goal_summary", "{}") ?: "{}")
            goalSummary = GoalSummary(
                target = goals.optDouble("target", 0.0),
                saved = goals.optDouble("saved", 0.0),
                completed = goals.optInt("completed", 0),
                active = goals.optInt("active", 0)
            )

            lastSuccessfulSync = prefs.getLong("last_successful_sync", 0L)
            records.sortByDescending { it.date }
            true
        } catch (exception: Exception) {
            android.util.Log.e("AnalyticsActivity", "Unable to read analytics cache", exception)
            records.clear()
            debtSummary = DebtSummary()
            goalSummary = GoalSummary()
            false
        }
    }

    private fun saveAnalyticsCache(uid: String) {
        val recordArray = JSONArray()
        records.forEach { record ->
            recordArray.put(JSONObject().apply {
                put("id", record.id)
                put("source", record.source)
                put("type", record.type)
                put("category", record.category)
                put("description", record.description)
                put("amount", record.amount)
                put("date", record.date.time)
                put("paydayPeriod", record.paydayPeriod)
                put("paymentMethod", record.paymentMethod)
                put("reference", record.reference)
                put("principal", record.principal)
                put("interest", record.interest)
                put("fees", record.fees)
            })
        }

        val debtJson = JSONObject().apply {
            put("principal", debtSummary.principal)
            put("repaid", debtSummary.repaid)
            put("outstanding", debtSummary.outstanding)
        }
        val goalsJson = JSONObject().apply {
            put("target", goalSummary.target)
            put("saved", goalSummary.saved)
            put("completed", goalSummary.completed)
            put("active", goalSummary.active)
        }

        cachePreferences(uid).edit()
            .putInt("cache_version", cacheVersion)
            .putString("records", recordArray.toString())
            .putString("debt_summary", debtJson.toString())
            .putString("goal_summary", goalsJson.toString())
            .putLong("last_successful_sync", lastSuccessfulSync)
            .putLong("cache_saved_at", System.currentTimeMillis())
            .apply()
    }

    // -------------------------------------------------------------------------
    // Build normalized records from Firestore
    // -------------------------------------------------------------------------

    private fun buildAnalyticsRecords(
        snapshots: Map<String, QuerySnapshot>
    ): List<AnalyticsRecord> {
        val result = mutableListOf<AnalyticsRecord>()

        snapshots["salaries"]?.documents.orEmpty().forEach { doc ->
            val date = dateFrom(doc) ?: return@forEach
            result.add(
                AnalyticsRecord(
                    id = doc.id,
                    source = "salaries",
                    type = "Income",
                    category = "Salary",
                    description = stringFrom(doc, "description", "salaryName", "period")
                        .ifBlank { "Salary received" },
                    amount = amountFrom(doc, "netSalary", "netAmount", "netPay", "takeHomePay", "amount"),
                    date = date,
                    paydayPeriod = paydayPeriodFrom(doc, date),
                    paymentMethod = stringFrom(doc, "paymentMethod", "method"),
                    reference = stringFrom(doc, "reference", "referenceNumber")
                )
            )
        }

        snapshots["expenses"]?.documents.orEmpty().forEach { doc ->
            val date = dateFrom(doc) ?: return@forEach
            result.add(
                AnalyticsRecord(
                    id = doc.id,
                    source = "expenses",
                    type = "Expense",
                    category = stringFrom(doc, "category").ifBlank { "Other" },
                    description = stringFrom(doc, "description", "name").ifBlank { "Expense" },
                    amount = amountFrom(doc, "amount", "total"),
                    date = date,
                    paydayPeriod = paydayPeriodFrom(doc, date),
                    paymentMethod = stringFrom(doc, "paymentMethod", "method"),
                    reference = stringFrom(doc, "reference", "referenceNumber")
                )
            )
        }

        snapshots["savings"]?.documents.orEmpty().forEach { doc ->
            val date = dateFrom(doc) ?: return@forEach
            val movementType = stringFrom(doc, "transactionType", "type", "movementType")
                .lowercase(Locale.ROOT)
            val withdrawal = movementType.contains("withdraw")
            result.add(
                AnalyticsRecord(
                    id = doc.id,
                    source = "savings",
                    type = if (withdrawal) "Savings Withdrawal" else "Savings Contribution",
                    category = stringFrom(doc, "accountName", "account", "category").ifBlank { "Savings" },
                    description = stringFrom(doc, "description", "accountName", "notes").ifBlank {
                        if (withdrawal) "Savings withdrawal" else "Savings contribution"
                    },
                    amount = amountFrom(doc, "amount", "transactionAmount"),
                    date = date,
                    paydayPeriod = paydayPeriodFrom(doc, date),
                    paymentMethod = stringFrom(doc, "paymentMethod", "method"),
                    reference = stringFrom(doc, "reference", "referenceNumber")
                )
            )
        }

        snapshots["payments"]?.documents.orEmpty().forEach { doc ->
            val date = dateFrom(doc) ?: return@forEach
            val paymentType = stringFrom(doc, "type", "paymentType", "category")
                .lowercase(Locale.ROOT)
            val principal = amountFrom(doc, "principal", "principalAmount")
            val interest = amountFrom(doc, "interest", "interestAmount")
            val fees = amountFrom(doc, "fees", "feeAmount")
            val loanPayment = paymentType.contains("loan") || principal > 0.0 || interest > 0.0 || fees > 0.0
            result.add(
                AnalyticsRecord(
                    id = doc.id,
                    source = "payments",
                    type = if (loanPayment) "Loan Payment" else "Bill Payment",
                    category = stringFrom(doc, "payee", "obligationName", "loanName", "category")
                        .ifBlank { if (loanPayment) "Loan" else "Bills" },
                    description = stringFrom(doc, "description", "payee", "obligationName", "loanName")
                        .ifBlank { if (loanPayment) "Loan payment" else "Bill payment" },
                    amount = amountFrom(doc, "amount", "paymentAmount", "paidAmount"),
                    date = date,
                    paydayPeriod = paydayPeriodFrom(doc, date),
                    paymentMethod = stringFrom(doc, "paymentMethod", "method"),
                    reference = stringFrom(doc, "reference", "referenceNumber"),
                    principal = principal,
                    interest = interest,
                    fees = fees
                )
            )
        }

        return result.sortedByDescending { it.date }
    }

    // -------------------------------------------------------------------------
    // Debt and goal summaries
    // -------------------------------------------------------------------------

    private fun calculateDebtSummary(documents: List<DocumentSnapshot>): DebtSummary {
        var principalTotal = 0.0
        var repaidTotal = 0.0
        var outstandingTotal = 0.0

        documents.forEach { doc ->
            val status = stringFrom(doc, "status").lowercase(Locale.ROOT)
            if (status in listOf("cancelled", "canceled", "rejected")) return@forEach

            val principal = amountFrom(doc, "principal", "loanPrincipal", "principalAmount")
            val repaid = amountFrom(doc, "totalRepaid", "amountPaid", "paidAmount")
            var outstanding = amountFrom(doc, "outstandingBalance", "remainingBalance", "currentBalance")

            if (outstanding <= 0.0) outstanding = (principal - repaid).coerceAtLeast(0.0)
            if (status in listOf("paid", "closed", "completed")) outstanding = 0.0

            principalTotal += principal
            repaidTotal += repaid
            outstandingTotal += outstanding
        }

        return DebtSummary(principalTotal, repaidTotal, outstandingTotal)
    }

    private fun calculateGoalSummary(documents: List<DocumentSnapshot>): GoalSummary {
        var targetTotal = 0.0
        var savedTotal = 0.0
        var completedCount = 0
        var activeCount = 0

        documents.forEach { doc ->
            val status = stringFrom(doc, "status").lowercase(Locale.ROOT)
            if (status in listOf("cancelled", "canceled", "rejected")) return@forEach

            val target = amountFrom(doc, "targetAmount", "target", "goalAmount")
            val saved = amountFrom(doc, "currentSaved", "savedAmount", "amountSaved", "saved")
            targetTotal += target
            savedTotal += saved

            val isComplete = status in listOf("completed", "complete", "achieved") ||
                    (target > 0.0 && saved >= target)
            if (isComplete) completedCount++ else activeCount++
        }

        return GoalSummary(targetTotal, savedTotal, completedCount, activeCount)
    }

    private fun renderDebtProgress() {
        val progress = if (debtSummary.principal > 0.0) {
            (debtSummary.repaid / debtSummary.principal * 100.0).roundToInt().coerceIn(0, 100)
        } else 0

        findViewById<ProgressBar>(R.id.progressDebtRepayment).apply {
            max = 100
            this.progress = progress
        }
        findViewById<TextView>(R.id.tvDebtRepaid).text = formatCurrency(debtSummary.repaid)
        findViewById<TextView>(R.id.tvDebtRemaining).text = formatCurrency(debtSummary.outstanding)
        findViewById<TextView>(R.id.tvDebtProgressPercentage).text = "$progress% of recorded debt repaid"
    }

    private fun renderGoalsProgress() {
        val progress = if (goalSummary.target > 0.0) {
            (goalSummary.saved / goalSummary.target * 100.0).roundToInt().coerceIn(0, 100)
        } else 0

        findViewById<ProgressBar>(R.id.progressGoals).apply {
            max = 100
            this.progress = progress
        }
        findViewById<TextView>(R.id.tvGoalsCompleted).text = goalSummary.completed.toString()
        findViewById<TextView>(R.id.tvGoalsActive).text = goalSummary.active.toString()
        findViewById<TextView>(R.id.tvGoalProgressPercentage).text = "$progress% overall goal completion"
    }

    private fun updateSavingsBalance() {
        val netSavings = records
            .filter { it.type == "Savings Contribution" || it.type == "Savings Withdrawal" }
            .sumOf { if (it.type == "Savings Contribution") it.amount else -it.amount }
            .coerceAtLeast(0.0)
        findViewById<TextView>(R.id.tvAnalyticsTotalSavings).text = formatCurrency(netSavings)
    }

    // -------------------------------------------------------------------------
    // Filter and summary calculations
    // -------------------------------------------------------------------------

    private fun applyAnalyticsFilters() {
        val categoryFilter = spCategory.selectedItem?.toString()?.trim().orEmpty()
        val typeFilter = spType.selectedItem?.toString()?.trim().orEmpty()

        selectedRecords.clear()
        selectedRecords.addAll(records.filter { record ->
            val inRange = !record.date.before(fromDate) && !record.date.after(toDate)
            val categoryMatches = categoryFilter.isBlank() ||
                    categoryFilter.contains("all", ignoreCase = true) ||
                    record.category.equals(categoryFilter, ignoreCase = true)
            inRange && categoryMatches && matchesType(record.type, typeFilter)
        })

        updateAnalyticsSummary()
        updateCharts()
        updateFilterStatus()
    }

    private fun matchesType(recordType: String, selectedType: String): Boolean {
        if (selectedType.isBlank() || selectedType.contains("all", ignoreCase = true)) return true
        val selected = selectedType.lowercase(Locale.ROOT)
        val actual = recordType.lowercase(Locale.ROOT)
        return when {
            selected.contains("income") -> actual == "income"
            selected == "expense" || selected.contains("expenses") -> actual == "expense"
            selected.contains("contribution") -> actual == "savings contribution"
            selected.contains("withdrawal") -> actual == "savings withdrawal"
            selected == "savings" -> actual.startsWith("savings")
            selected.contains("bill") -> actual == "bill payment"
            selected.contains("loan") -> actual == "loan payment"
            else -> actual.contains(selected)
        }
    }

    private fun updateAnalyticsSummary() {
        var income = 0.0
        var expenses = 0.0
        var payments = 0.0
        var contributions = 0.0
        var withdrawals = 0.0

        selectedRecords.forEach { record ->
            when (record.type) {
                "Income" -> income += record.amount
                "Expense" -> expenses += record.amount
                "Bill Payment", "Loan Payment" -> payments += record.amount
                "Savings Contribution" -> contributions += record.amount
                "Savings Withdrawal" -> withdrawals += record.amount
            }
        }

        val netSavings = contributions - withdrawals
        val netCashFlow = income - expenses - payments - contributions + withdrawals
        val savingsRate = if (income > 0.0) netSavings / income * 100.0 else 0.0

        tvTotalIncome.text = formatCurrency(income)
        tvTotalExpenses.text = formatCurrency(expenses)
        tvNetCashFlow.text = formatCurrency(netCashFlow)
        tvSavingsRate.text = String.format(Locale.US, "%.1f%%", savingsRate)
        tvNetCashFlow.setTextColor(if (netCashFlow >= 0.0) green else red)

        updateOptionalText("tvAnalyticsPayments", formatCurrency(payments))
        updateOptionalText("tvAnalyticsSavingsContributions", formatCurrency(contributions))
        updateOptionalText("tvAnalyticsSavingsWithdrawals", formatCurrency(withdrawals))
        updateOptionalText("tvAnalyticsRecordCount", "${selectedRecords.size} records")
        updateOptionalText("tvCashFlowDescription", "Income less expenses, payments and net savings transfers")
        updateOptionalText("tvSavingsRateDescription", "Net savings movement divided by income")
        findViewById<TextView>(R.id.tvAnalyticsTotalSavings).text = formatCurrency(netSavings)
        updateComparisonLabels(income, expenses)
    }

    private fun updateComparisonLabels(currentIncome: Double, currentExpenses: Double) {
        val duration = (toDate.time - fromDate.time + 1L).coerceAtLeast(1L)
        val previousStart = Date(fromDate.time - duration)
        val previousEnd = Date(fromDate.time - 1L)
        val previousRecords = records.filter { !it.date.before(previousStart) && !it.date.after(previousEnd) }
        val previousIncome = previousRecords.filter { it.type == "Income" }.sumOf { it.amount }
        val previousExpenses = previousRecords.filter { it.type == "Expense" }.sumOf { it.amount }

        val incomeView = findViewById<TextView>(R.id.tvIncomeComparison)
        val expenseView = findViewById<TextView>(R.id.tvExpenseComparison)
        incomeView.text = comparisonText(currentIncome, previousIncome)
        expenseView.text = comparisonText(currentExpenses, previousExpenses)
        incomeView.setTextColor(if (currentIncome >= previousIncome) green else red)
        // For expenses, a decrease is generally favorable.
        expenseView.setTextColor(if (currentExpenses <= previousExpenses) green else red)
    }

    private fun comparisonText(current: Double, previous: Double): String {
        if (previous <= 0.0) return if (current > 0.0) "New activity vs previous period" else "No previous-period activity"
        val change = ((current - previous) / previous) * 100.0
        val prefix = if (change > 0.05) "+" else ""
        return String.format(Locale.US, "%s%.1f%% vs previous period", prefix, change)
    }

    // -------------------------------------------------------------------------
    // Dynamic charts
    // -------------------------------------------------------------------------

    private fun updateCharts() {
        renderIncomeExpenseChart()
        renderCategoryChart()
        renderPaydayChart()
        renderSavingsChart()
    }

    private fun renderIncomeExpenseChart() {
        val container = findViewById<FrameLayout>(R.id.chartIncomeExpense)
        container.removeAllViews()
        if (selectedRecords.isEmpty()) {
            addEmptyChartMessage(container, "No records for this period.")
            return
        }

        val monthly = mutableMapOf<String, MonthlySummary>()
        selectedRecords.forEach { record ->
            val summary = monthly.getOrPut(monthKey(record.date)) { MonthlySummary() }
            when (record.type) {
                "Income" -> summary.income += record.amount
                "Expense" -> summary.expenses += record.amount
            }
        }
        val maximum = monthly.values.maxOfOrNull { maxOf(it.income, it.expenses) }?.coerceAtLeast(1.0) ?: 1.0
        val content = createChartContent()
        monthly.keys.sorted().forEach { month ->
            val summary = monthly[month] ?: return@forEach
            addSectionLabel(content, monthLabel(month))
            addBarRow(content, "Income", summary.income, maximum, green)
            addBarRow(content, "Expenses", summary.expenses, maximum, red)
        }
        container.addView(content)
    }

    private fun renderCategoryChart() {
        val container = findViewById<FrameLayout>(R.id.chartCategorySpending)
        container.removeAllViews()
        val grouped = selectedRecords.filter { it.type == "Expense" }
            .groupBy { it.category }
            .mapValues { (_, items) -> items.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }

        if (grouped.isEmpty()) {
            addEmptyChartMessage(container, "No expense categories found.")
            findViewById<LinearLayout>(R.id.categoryBreakdownContainer).removeAllViews()
            return
        }

        val maximum = grouped.maxOf { it.second }.coerceAtLeast(1.0)
        val content = createChartContent()
        grouped.take(8).forEach { (category, amount) -> addBarRow(content, category, amount, maximum, purple) }
        container.addView(content)

        val breakdown = findViewById<LinearLayout>(R.id.categoryBreakdownContainer)
        breakdown.removeAllViews()
        val breakdownContent = createChartContent()
        grouped.take(10).forEach { (category, amount) -> addBarRow(breakdownContent, category, amount, maximum, purple) }
        breakdown.addView(breakdownContent)
    }

    private fun renderPaydayChart() {
        val container = findViewById<FrameLayout>(R.id.chartPaydayCashFlow)
        container.removeAllViews()
        val periods = listOf("15th salary period", "Month-end salary period")
        val grouped = periods.associateWith { MonthlySummary() }.toMutableMap()

        selectedRecords.forEach { record ->
            val summary = grouped.getOrPut(record.paydayPeriod) { MonthlySummary() }
            when (record.type) {
                "Income" -> summary.income += record.amount
                "Expense" -> summary.expenses += record.amount
                "Bill Payment", "Loan Payment" -> summary.payments += record.amount
                "Savings Contribution" -> summary.contributions += record.amount
                "Savings Withdrawal" -> summary.withdrawals += record.amount
            }
        }

        val maximum = grouped.values.maxOfOrNull {
            maxOf(it.income, it.expenses, it.payments, it.contributions, it.withdrawals)
        }?.coerceAtLeast(1.0) ?: 1.0

        val content = createChartContent()
        periods.forEach { period ->
            val summary = grouped[period] ?: return@forEach
            addSectionLabel(content, period)
            addBarRow(content, "Income", summary.income, maximum, green)
            addBarRow(content, "Expenses", summary.expenses, maximum, red)
            addBarRow(content, "Bill and loan payments", summary.payments, maximum, orange)
            val netTransfers = (summary.contributions - summary.withdrawals).coerceAtLeast(0.0)
            addBarRow(content, "Net savings transfers", netTransfers, maximum, purple)
        }
        container.addView(content)
    }

    private fun renderSavingsChart() {
        val container = findViewById<FrameLayout>(R.id.chartSavingsGrowth)
        container.removeAllViews()
        val monthly = mutableMapOf<String, MonthlySummary>()
        selectedRecords.forEach { record ->
            if (record.type != "Savings Contribution" && record.type != "Savings Withdrawal") return@forEach
            val summary = monthly.getOrPut(monthKey(record.date)) { MonthlySummary() }
            if (record.type == "Savings Contribution") summary.contributions += record.amount
            else summary.withdrawals += record.amount
        }

        if (monthly.isEmpty()) {
            addEmptyChartMessage(container, "No savings movements in this period.")
            return
        }

        val maximum = monthly.values.maxOf { maxOf(it.contributions, it.withdrawals) }.coerceAtLeast(1.0)
        val content = createChartContent()
        monthly.keys.sorted().forEach { month ->
            val summary = monthly[month] ?: return@forEach
            addSectionLabel(content, monthLabel(month))
            addBarRow(content, "Contributions", summary.contributions, maximum, green)
            addBarRow(content, "Withdrawals", summary.withdrawals, maximum, red)
            val net = summary.contributions - summary.withdrawals
            addBarRow(content, if (net >= 0) "Net savings added" else "Net savings withdrawn", abs(net), maximum, purple)
        }
        container.addView(content)
    }

    private fun createChartContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addSectionLabel(parent: LinearLayout, label: String) {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(darkText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(7))
        })
    }

    private fun addBarRow(parent: LinearLayout, label: String, amount: Double, maximum: Double, color: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = formatCurrency(amount)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        })
        parent.addView(row)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            this.progress = ((amount / maximum.coerceAtLeast(1.0)) * 1000.0).roundToInt().coerceIn(0, 1000)
            progressTintList = ColorStateList.valueOf(color)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(238, 239, 245))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                topMargin = dp(3)
                bottomMargin = dp(7)
            }
        }
        parent.addView(progress)
    }

    private fun addEmptyChartMessage(container: ViewGroup, message: String) {
        container.addView(TextView(this).apply {
            text = message
            setTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(25), dp(15), dp(25))
        }, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    // -------------------------------------------------------------------------
    // CSV export
    // -------------------------------------------------------------------------

    private fun exportAnalytics() {
        if (selectedRecords.isEmpty()) {
            showToast("There are no analytics records to export.")
            return
        }
        savedCsvContent = buildCsvContent()
        val fileName = "MyFinance_Analytics_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
        exportCsvLauncher.launch(fileName)
    }

    private fun buildCsvContent(): String {
        val income = selectedRecords.filter { it.type == "Income" }.sumOf { it.amount }
        val expenses = selectedRecords.filter { it.type == "Expense" }.sumOf { it.amount }
        val payments = selectedRecords.filter { it.type == "Bill Payment" || it.type == "Loan Payment" }.sumOf { it.amount }
        val contributions = selectedRecords.filter { it.type == "Savings Contribution" }.sumOf { it.amount }
        val withdrawals = selectedRecords.filter { it.type == "Savings Withdrawal" }.sumOf { it.amount }
        val netCashFlow = income - expenses - payments - contributions + withdrawals

        return buildString {
            appendLine("MY FINANCE - ANALYTICS REPORT")
            appendLine("From,${csv(formatDate(fromDate))}")
            appendLine("To,${csv(formatDate(toDate))}")
            appendLine()
            appendLine("FINANCIAL SUMMARY")
            appendLine("Metric,Amount")
            appendLine("Income,$income")
            appendLine("Expenses,$expenses")
            appendLine("Bill and Loan Payments,$payments")
            appendLine("Savings Contributions,$contributions")
            appendLine("Savings Withdrawals,$withdrawals")
            appendLine("Net Cash Flow,$netCashFlow")
            appendLine()
            appendLine("Date,Type,Description,Category,Amount,Payday Period,Payment Method,Reference,Source")
            selectedRecords.sortedBy { it.date }.forEach { record ->
                appendLine(listOf(
                    csv(formatDate(record.date)),
                    csv(record.type),
                    csv(record.description),
                    csv(record.category),
                    record.amount.toString(),
                    csv(record.paydayPeriod),
                    csv(record.paymentMethod),
                    csv(record.reference),
                    csv(record.source)
                ).joinToString(","))
            }
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun writeCsvFile(uri: Uri) {
        try {
            val output = contentResolver.openOutputStream(uri)
            if (output == null) {
                showToast("Unable to create the export file.")
                return
            }
            output.use { it.write(savedCsvContent.toByteArray(Charsets.UTF_8)) }
            showToast("Analytics CSV exported successfully.")
        } catch (exception: Exception) {
            showToast("Export failed: ${exception.localizedMessage ?: "Unknown error"}")
        }
    }

    // -------------------------------------------------------------------------
    // Firestore field/date helpers
    // -------------------------------------------------------------------------

    private fun amountFrom(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            val value = document.get(field) ?: continue
            val amount = parseAmount(value)
            if (amount != 0.0) return amount
        }
        return 0.0
    }

    private fun parseAmount(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val text = value.trim()
                if (text.isBlank()) return 0.0
                val accountingNegative = text.startsWith("(") && text.endsWith(")")
                val cleaned = text.replace(Regex("[^0-9.\\-]"), "")
                val parsed = cleaned.toDoubleOrNull() ?: return 0.0
                if (accountingNegative && parsed > 0.0) -parsed else parsed
            }
            else -> 0.0
        }
    }

    private fun stringFrom(document: DocumentSnapshot, vararg fields: String): String {
        for (field in fields) {
            val value = document.get(field)
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun dateFrom(document: DocumentSnapshot): Date? {
        val fields = listOf(
            "date", "transactionDate", "paymentDate", "salaryDate",
            "dateString", "createdAt", "timestamp"
        )
        for (field in fields) {
            when (val value = document.get(field)) {
                is Timestamp -> return value.toDate()
                is Date -> return value
                is Number -> {
                    val raw = value.toLong()
                    return Date(if (raw < 100000000000L) raw * 1000L else raw)
                }
                is String -> parseDate(value)?.let { return it }
            }
        }
        return null
    }

    private fun parseDate(value: String): Date? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "MMM d, yyyy",
            "MMMM d, yyyy"
        )
        patterns.forEach { pattern ->
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = timezone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) {
                // Try the next supported format.
            }
        }
        return null
    }

    private fun paydayPeriodFrom(document: DocumentSnapshot, date: Date): String {
        val saved = stringFrom(document, "paydayPeriodLabel", "paydayPeriod", "period")
        if (saved.isNotBlank()) {
            return when {
                saved.contains("15", ignoreCase = true) -> "15th salary period"
                saved.contains("month_end", ignoreCase = true) ||
                        saved.contains("month-end", ignoreCase = true) ||
                        saved.contains("month end", ignoreCase = true) ||
                        saved.contains("1st", ignoreCase = true) -> "Month-end salary period"
                else -> saved
            }
        }
        val calendar = Calendar.getInstance(timezone).apply { time = date }
        return if (calendar.get(Calendar.DAY_OF_MONTH) >= 15) {
            "15th salary period"
        } else {
            "Month-end salary period"
        }
    }

    // -------------------------------------------------------------------------
    // Formatting and optional UI helpers
    // -------------------------------------------------------------------------

    private fun monthKey(date: Date): String = SimpleDateFormat("yyyy-MM", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun monthLabel(key: String): String {
        return try {
            val parsed = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = timezone }.parse(key)
                ?: return key
            SimpleDateFormat("MMMM yyyy", Locale.US).apply { timeZone = timezone }.format(parsed)
        } catch (_: Exception) {
            key
        }
    }

    private fun formatCurrency(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(amount)

    private fun formatDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun formatDateTime(date: Date): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).apply {
        timeZone = timezone
    }.format(date)



    private fun updateFilterStatus() {
        val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US).apply { timeZone = timezone }
        tvFilterStatus.text = "Showing ${selectedRecords.size} record(s) from ${formatter.format(fromDate)} to ${formatter.format(toDate)}"
    }

    private fun updateLastUpdatedLabel() {
        if (lastSuccessfulSync > 0L) {
            updateOptionalText("tvAnalyticsLastUpdated", "Last cloud sync: ${formatDateTime(Date(lastSuccessfulSync))}")
        } else {
            updateOptionalText("tvAnalyticsLastUpdated", "No successful cloud sync yet")
        }
    }

    private fun updateSyncStatus(message: String) {
        updateOptionalText("tvAnalyticsSyncStatus", message)
    }

    private fun updateOptionalText(viewName: String, value: String) {
        val id = resources.getIdentifier(viewName, "id", packageName)
        if (id == 0) return
        val view = findViewById<View>(id)
        if (view is TextView) {
            view.text = value
            view.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        val loadingView = findViewById<View>(R.id.analyticsLoading)
        loadingView.visibility = if (loading) View.VISIBLE else View.GONE
        btnApplyFilters.isEnabled = !loading
        btnExportAnalytics.isEnabled = !loading
        if (loading && records.isEmpty()) {
            tvFilterStatus.text = "Loading financial records…"
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

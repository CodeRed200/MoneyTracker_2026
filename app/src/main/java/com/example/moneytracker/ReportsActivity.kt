package com.example.moneytracker

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Financial Reports
 *
 * Firestore collections read by this screen:
 * users/{uid}/salaries
 * users/{uid}/expenses
 * users/{uid}/savings
 * users/{uid}/payments
 * users/{uid}/loans
 * users/{uid}/obligations
 *
 * All report data is cached in a user-specific SharedPreferences file. The screen
 * renders the cache first, then refreshes each collection from Firestore. It does
 * not write, edit, or delete financial transactions.
 */
class ReportsActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // FIREBASE / USER-SCOPED CACHE
    // -------------------------------------------------------------------------

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var userId: String = ""
    private val timezone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private val collectionNames = listOf(
        "salaries",
        "expenses",
        "savings",
        "payments",
        "loans",
        "obligations"
    )

    private data class CachedDocument(
        val id: String,
        val fields: Map<String, Any?>
    )

    private val cachedCollections = mutableMapOf<String, List<CachedDocument>>()
    private var profileFields: Map<String, Any?> = emptyMap()
    private var loadGeneration = 0
    private var lastUpdatedMillis = 0L
    private var showingCachedData = false

    private val cachePreferences by lazy {
        getSharedPreferences("reports_cache_$userId", MODE_PRIVATE)
    }

    // -------------------------------------------------------------------------
    // REPORT MODE / DATE
    // -------------------------------------------------------------------------

    private val reportCalendar: Calendar = Calendar.getInstance(timezone)
    private var annualMode = false

    private val currencyFormatter: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    private val monthFormatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).apply {
        timeZone = timezone
    }
    private val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply {
        timeZone = timezone
    }
    private val dateTimeFormatter =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).apply {
            timeZone = timezone
        }

    // -------------------------------------------------------------------------
    // REPORT DATA MODELS
    // -------------------------------------------------------------------------

    private data class ReportItem(
        val id: String,
        val title: String,
        val category: String,
        val amount: Double,
        val date: Long,
        val detail: String = "",
        val isWithdrawal: Boolean = false
    )

    private data class PaymentRecord(
        val id: String,
        val obligationId: String,
        val loanId: String,
        val description: String,
        val amount: Double,
        val date: Long,
        val method: String,
        val principal: Double,
        val interest: Double,
        val fees: Double,
        val reference: String,
        val source: String
    )

    private data class PaydayTotals(
        val income: Double,
        val expenses: Double
    ) {
        val remaining: Double get() = income - expenses
    }

    private data class ReportSnapshot(
        val salaries: List<ReportItem>,
        val expenses: List<ReportItem>,
        val savings: List<ReportItem>,
        val payments: List<PaymentRecord>,
        val income: Double,
        val expenseTotal: Double,
        val contributions: Double,
        val withdrawals: Double,
        val paymentTotal: Double,
        val netCashFlow: Double,
        val principalRepaid: Double,
        val interestPaid: Double,
        val feesPaid: Double,
        val outstandingDebt: Double,
        val unpaidObligations: Double,
        val categories: List<Pair<String, Double>>,
        val firstPayday: PaydayTotals,
        val secondPayday: PaydayTotals
    )

    private var currentSnapshot: ReportSnapshot? = null
    private var currentCsv: String = ""

    // -------------------------------------------------------------------------
    // CSV FILE CREATION
    // -------------------------------------------------------------------------

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(currentCsv.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw IllegalStateException("Could not open the selected file.")

                findViewById<TextView>(R.id.tvReportExportStatus).text =
                    "CSV report exported successfully."
                showMessage("CSV report exported successfully.")
            } catch (exception: Exception) {
                showError("Unable to export CSV: ${exception.message ?: "Unknown error"}")
            }
        }

    // -------------------------------------------------------------------------
    // ACTIVITY
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_reports)

        setupWindowInsets()
        setupClickListeners()
        updatePeriodDisplay()
        checkUserSession()
    }

    override fun onDestroy() {
        loadGeneration++
        super.onDestroy()
    }

    private fun checkUserSession() {
        val user = auth.currentUser
        if (user == null) {
            navigateToLogin()
            return
        }
        if (!user.isEmailVerified) {
            Toast.makeText(
                this,
                "Please verify your email before viewing financial reports.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, EmailVerificationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        userId = user.uid
        loadLocalCache()
        if (hasCachedData()) {
            renderReport()
            findViewById<TextView>(R.id.tvReportLastUpdated).text =
                if (lastUpdatedMillis > 0L) {
                    "Showing saved data · ${dateTimeFormatter.format(Date(lastUpdatedMillis))}"
                } else {
                    "Showing saved data from this device"
                }
        }
        refreshFromFirebase()
    }

    private fun navigateToLogin() {
        startActivity(
            Intent(this, Login::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    // -------------------------------------------------------------------------
    // WINDOW INSETS / CLICK LISTENERS
    // -------------------------------------------------------------------------

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnReportsBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnMonthlyReport).setOnClickListener {
            setReportMode(false)
        }
        findViewById<View>(R.id.btnAnnualReport).setOnClickListener {
            setReportMode(true)
        }

        findViewById<View>(R.id.btnPreviousReportMonth).setOnClickListener {
            reportCalendar.add(Calendar.MONTH, -1)
            renderReport()
        }
        findViewById<View>(R.id.btnNextReportMonth).setOnClickListener {
            val next = reportCalendar.clone() as Calendar
            next.add(Calendar.MONTH, 1)
            if (isFuturePeriod(next)) {
                showMessage("Future reports are not available yet.")
                return@setOnClickListener
            }
            reportCalendar.add(Calendar.MONTH, 1)
            renderReport()
        }
        findViewById<View>(R.id.btnPreviousReportYear).setOnClickListener {
            reportCalendar.add(Calendar.YEAR, -1)
            renderReport()
        }
        findViewById<View>(R.id.btnNextReportYear).setOnClickListener {
            val next = reportCalendar.clone() as Calendar
            next.add(Calendar.YEAR, 1)
            if (isFuturePeriod(next)) {
                showMessage("Future reports are not available yet.")
                return@setOnClickListener
            }
            reportCalendar.add(Calendar.YEAR, 1)
            renderReport()
        }

        findViewById<View>(R.id.btnGenerateReport).setOnClickListener {
            renderReport()
            findViewById<TextView>(R.id.tvReportExportStatus).text =
                "Report generated for ${selectedPeriodLabel()}"
        }
        findViewById<View>(R.id.btnRefreshReports).setOnClickListener {
            refreshFromFirebase()
        }
        findViewById<View>(R.id.btnDownloadReportCsv).setOnClickListener { exportCsv() }
        findViewById<View>(R.id.btnPrintReport).setOnClickListener { printReport() }
        findViewById<View>(R.id.btnEmailReport).setOnClickListener { emailReport() }
    }

    // -------------------------------------------------------------------------
    // FIREBASE REFRESH
    // -------------------------------------------------------------------------

    private fun refreshFromFirebase() {
        if (userId.isBlank()) return

        val generation = ++loadGeneration
        val userRef = firestore.collection("users").document(userId)
        val totalTasks = collectionNames.size + 1 // collections plus user profile
        var completedTasks = 0
        var cacheResponses = 0
        val failedSources = mutableListOf<String>()

        findViewById<View>(R.id.reportLoading).visibility = View.VISIBLE
        findViewById<View>(R.id.tvReportError).visibility = View.GONE
        findViewById<TextView>(R.id.tvReportLastUpdated).text =
            if (hasCachedData()) "Refreshing saved report data…" else "Loading your financial records…"

        fun taskFinished(sourceFromCache: Boolean = false, failure: String? = null) {
            if (generation != loadGeneration || isFinishing || isDestroyed) return
            if (sourceFromCache) cacheResponses++
            if (!failure.isNullOrBlank()) failedSources.add(failure)
            completedTasks++
            if (completedTasks < totalTasks) return

            findViewById<View>(R.id.reportLoading).visibility = View.GONE
            showingCachedData = cacheResponses > 0 || failedSources.isNotEmpty()
            if (failedSources.isNotEmpty()) {
                findViewById<TextView>(R.id.tvReportError).apply {
                    text = "Some data could not be refreshed (${failedSources.joinToString(", ")}). Showing available saved records."
                    visibility = View.VISIBLE
                }
            } else {
                findViewById<View>(R.id.tvReportError).visibility = View.GONE
            }

            if (failedSources.isEmpty() && cacheResponses == 0) {
                lastUpdatedMillis = System.currentTimeMillis()
            }
            cachePreferences.edit().putLong("last_updated", lastUpdatedMillis).apply()
            renderReport()
            findViewById<TextView>(R.id.tvReportLastUpdated).text = when {
                failedSources.isNotEmpty() -> "Showing saved/available records · last successful sync ${formatCachedTime()}"
                showingCachedData -> "Showing Firebase cached records · ${formatCachedTime()}"
                else -> "Synced with Firebase · ${formatCachedTime()}"
            }
        }

        collectionNames.forEach { collectionName ->
            userRef.collection(collectionName).get()
                .addOnSuccessListener { snapshot ->
                    if (generation != loadGeneration) return@addOnSuccessListener
                    val remoteDocuments = snapshot.documents.mapNotNull { document ->
                        val fields = document.data ?: return@mapNotNull null
                        CachedDocument(document.id, normalizeMap(fields))
                    }
                    val oldDocuments = cachedCollections[collectionName].orEmpty()
                    val preserveOldEmptyCache =
                        snapshot.metadata.isFromCache && remoteDocuments.isEmpty() && oldDocuments.isNotEmpty()
                    if (!preserveOldEmptyCache) {
                        cachedCollections[collectionName] = remoteDocuments
                        saveCollectionCache(collectionName, remoteDocuments)
                    }
                    taskFinished(snapshot.metadata.isFromCache)
                }
                .addOnFailureListener { error ->
                    taskFinished(failure = collectionName)
                    android.util.Log.w("ReportsActivity", "Unable to refresh $collectionName", error)
                }
        }

        userRef.get()
            .addOnSuccessListener { snapshot ->
                if (generation != loadGeneration) return@addOnSuccessListener
                val remoteProfile = snapshot.data?.let { normalizeMap(it) }.orEmpty()
                val preserveProfile = snapshot.metadata.isFromCache && remoteProfile.isEmpty() && profileFields.isNotEmpty()
                if (!preserveProfile) {
                    profileFields = remoteProfile
                    saveProfileCache()
                }
                taskFinished(snapshot.metadata.isFromCache)
            }
            .addOnFailureListener { error ->
                taskFinished(failure = "profile")
                android.util.Log.w("ReportsActivity", "Unable to refresh user profile", error)
            }
    }

    // -------------------------------------------------------------------------
    // USER-SCOPED LOCAL CACHE
    // -------------------------------------------------------------------------

    private fun hasCachedData(): Boolean =
        cachedCollections.values.any { it.isNotEmpty() } || profileFields.isNotEmpty()

    private fun loadLocalCache() {
        cachedCollections.clear()
        collectionNames.forEach { collectionName ->
            val raw = cachePreferences.getString("collection_$collectionName", null) ?: return@forEach
            try {
                val array = JSONArray(raw)
                val documents = mutableListOf<CachedDocument>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val fieldsJson = item.optJSONObject("fields") ?: JSONObject()
                    if (id.isNotBlank()) {
                        documents.add(CachedDocument(id, jsonObjectToMap(fieldsJson)))
                    }
                }
                cachedCollections[collectionName] = documents
            } catch (exception: Exception) {
                android.util.Log.w("ReportsActivity", "Could not read $collectionName cache", exception)
            }
        }

        try {
            val profileJson = cachePreferences.getString("profile_json", null)
            if (!profileJson.isNullOrBlank()) {
                profileFields = jsonObjectToMap(JSONObject(profileJson))
            }
        } catch (exception: Exception) {
            android.util.Log.w("ReportsActivity", "Could not read profile cache", exception)
        }
        lastUpdatedMillis = cachePreferences.getLong("last_updated", 0L)
    }

    private fun saveCollectionCache(name: String, documents: List<CachedDocument>) {
        val array = JSONArray()
        documents.forEach { document ->
            val item = JSONObject()
            item.put("id", document.id)
            item.put("fields", mapToJsonObject(document.fields))
            array.put(item)
        }
        cachePreferences.edit().putString("collection_$name", array.toString()).apply()
    }

    private fun saveProfileCache() {
        cachePreferences.edit()
            .putString("profile_json", mapToJsonObject(profileFields).toString())
            .apply()
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val result = JSONObject()
        map.forEach { (key, value) -> result.put(key, toJsonValue(value)) }
        return result
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Timestamp -> value.toDate().time
        is Date -> value.time
        is Map<*, *> -> {
            val nested = JSONObject()
            value.forEach { (key, item) -> if (key != null) nested.put(key.toString(), toJsonValue(item)) }
            nested
        }
        is Iterable<*> -> {
            val array = JSONArray()
            value.forEach { array.put(toJsonValue(it)) }
            array
        }
        is Array<*> -> {
            val array = JSONArray()
            value.forEach { array.put(toJsonValue(it)) }
            array
        }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = fromJsonValue(json.opt(key))
        }
        return result
    }

    private fun fromJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { fromJsonValue(value.opt(it)) }
        else -> value
    }

    private fun normalizeMap(source: Map<String, Any?>): Map<String, Any?> =
        source.mapValues { (_, value) -> normalizeFirestoreValue(value) }

    private fun normalizeFirestoreValue(value: Any?): Any? = when (value) {
        null -> null
        is Timestamp -> value.toDate().time
        is Date -> value.time
        is Map<*, *> -> value.entries.associate { (key, nested) ->
            key.toString() to normalizeFirestoreValue(nested)
        }
        is List<*> -> value.map { normalizeFirestoreValue(it) }
        is Number, is String, is Boolean -> value
        else -> value.toString()
    }

    // -------------------------------------------------------------------------
    // REPORT MODE / PERIOD HELPERS
    // -------------------------------------------------------------------------

    private fun setReportMode(annual: Boolean) {
        annualMode = annual
        findViewById<View>(R.id.reportMonthSection).visibility =
            if (annual) View.GONE else View.VISIBLE
        findViewById<View>(R.id.reportYearSection).visibility =
            if (annual) View.VISIBLE else View.GONE

        val monthlyButton = findViewById<TextView>(R.id.btnMonthlyReport)
        val annualButton = findViewById<TextView>(R.id.btnAnnualReport)
        if (annual) {
            monthlyButton.background = null
            monthlyButton.setTextColor(Color.parseColor("#687087"))
            annualButton.background = roundedBackground("#6558D3")
            annualButton.setTextColor(Color.WHITE)
        } else {
            annualButton.background = null
            annualButton.setTextColor(Color.parseColor("#687087"))
            monthlyButton.background = roundedBackground("#6558D3")
            monthlyButton.setTextColor(Color.WHITE)
        }
        updatePeriodDisplay()
        renderReport()
    }

    private fun updatePeriodDisplay() {
        findViewById<TextView>(R.id.tvReportMonth).text = monthFormatter.format(reportCalendar.time)
        findViewById<TextView>(R.id.tvReportYear).text = reportCalendar.get(Calendar.YEAR).toString()
        findViewById<TextView>(R.id.tvReportPeriodDescription).text =
            if (annualMode) {
                "Showing financial activity for ${reportCalendar.get(Calendar.YEAR)}."
            } else {
                "Showing ${monthFormatter.format(reportCalendar.time)} financial activity."
            }
    }

    private fun isFuturePeriod(calendar: Calendar): Boolean {
        val current = Calendar.getInstance(timezone)
        return if (annualMode) {
            calendar.get(Calendar.YEAR) > current.get(Calendar.YEAR)
        } else {
            calendar.get(Calendar.YEAR) > current.get(Calendar.YEAR) ||
                    (calendar.get(Calendar.YEAR) == current.get(Calendar.YEAR) &&
                            calendar.get(Calendar.MONTH) > current.get(Calendar.MONTH))
        }
    }

    private fun isInReportPeriod(timestamp: Long): Boolean {
        val date = Calendar.getInstance(timezone).apply { timeInMillis = timestamp }
        if (date.get(Calendar.YEAR) != reportCalendar.get(Calendar.YEAR)) return false
        return annualMode || date.get(Calendar.MONTH) == reportCalendar.get(Calendar.MONTH)
    }

    private fun selectedPeriodLabel(): String = if (annualMode) {
        reportCalendar.get(Calendar.YEAR).toString()
    } else {
        monthFormatter.format(reportCalendar.time)
    }

    private fun periodRecords(
        collection: String,
        dateFieldNames: List<String>
    ): List<Pair<CachedDocument, Long>> {
        return cachedCollections[collection].orEmpty().mapNotNull { document ->
            val timestamp = dateFrom(document.fields, dateFieldNames) ?: return@mapNotNull null
            if (isInReportPeriod(timestamp)) document to timestamp else null
        }.sortedByDescending { it.second }
    }

    // -------------------------------------------------------------------------
    // REPORT GENERATION
    // -------------------------------------------------------------------------

    private fun renderReport() {
        updatePeriodDisplay()
        findViewById<View>(R.id.tvReportError).visibility = View.GONE
        try {
            val snapshot = buildReportSnapshot()
            currentSnapshot = snapshot
            updateFinancialSummary(snapshot)
            updateCharts(snapshot)
            updateCategoryBreakdown(snapshot.categories)
            updatePaydayBreakdown(snapshot.firstPayday, snapshot.secondPayday)
            updateFinancialDetails(snapshot)
            findViewById<TextView>(R.id.tvReportLastUpdated).text =
                if (lastUpdatedMillis > 0L) "Last data refresh: ${dateTimeFormatter.format(Date(lastUpdatedMillis))}" else "Report generated from available recorded transactions."
        } catch (exception: Exception) {
            showError("Unable to generate report: ${exception.message ?: "Unknown error"}")
        }
    }

    private fun buildReportSnapshot(): ReportSnapshot {
        val salaryEntries = periodRecords(
            "salaries",
            listOf("salaryDate", "salaryDateMillis", "paydayDate", "payDate", "paymentDate", "dateMillis", "date", "dateString", "createdAt")
        ).mapNotNull { (document, date) -> toReportItem(document, date, "salary") }

        val expenseEntries = periodRecords(
            "expenses",
            listOf("dateMillis", "expenseDateMillis", "date", "transactionDate", "dateString", "createdAt")
        ).mapNotNull { (document, date) -> toReportItem(document, date, "expense") }

        val savingEntries = periodRecords(
            "savings",
            listOf("dateMillis", "date", "transactionDate", "dateString", "createdAt")
        ).mapNotNull { (document, date) -> toReportItem(document, date, "saving") }

        val paymentDocuments = periodRecords(
            "payments",
            listOf("dateMillis", "paymentDateMillis", "paymentDate", "date", "transactionDate", "dateString", "createdAt")
        )
        val paymentEntries = paymentDocuments.map { (document, date) -> toPaymentRecord(document, date) }

        val incomeTotal = salaryEntries.sumOf { it.amount }
        val expensesTotal = expenseEntries.sumOf { it.amount }
        val contributions = savingEntries.filterNot { it.isWithdrawal }.sumOf { it.amount }
        val withdrawals = savingEntries.filter { it.isWithdrawal }.sumOf { it.amount }
        val paymentsTotal = paymentEntries.sumOf { it.amount }
        val netCashFlow = incomeTotal - expensesTotal - paymentsTotal - contributions + withdrawals

        val loanPayments = paymentEntries.filter {
            it.source.contains("loan", ignoreCase = true) || it.loanId.isNotBlank()
        }
        val principalRepaid = loanPayments.sumOf { it.principal }
        val interestPaid = loanPayments.sumOf { it.interest }
        val feesPaid = loanPayments.sumOf { it.fees }

        val categoryTotals = expenseEntries.groupBy { it.category.ifBlank { "Uncategorized" } }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }

        return ReportSnapshot(
            salaries = salaryEntries,
            expenses = expenseEntries,
            savings = savingEntries,
            payments = paymentEntries,
            income = incomeTotal,
            expenseTotal = expensesTotal,
            contributions = contributions,
            withdrawals = withdrawals,
            paymentTotal = paymentsTotal,
            netCashFlow = netCashFlow,
            principalRepaid = principalRepaid,
            interestPaid = interestPaid,
            feesPaid = feesPaid,
            outstandingDebt = calculateOutstandingLoanDebt(),
            unpaidObligations = calculateUnpaidObligations(),
            categories = categoryTotals,
            firstPayday = calculatePaydayPeriod(firstPayday = true),
            secondPayday = calculatePaydayPeriod(firstPayday = false)
        )
    }

    private fun toReportItem(document: CachedDocument, date: Long, type: String): ReportItem? {
        val fields = document.fields
        val amount = when (type) {
            "salary" -> amountFrom(fields, "netSalary", "netAmount", "netPay", "takeHomePay", "salaryAmount", "amount", "grossSalary")
            "expense" -> amountFrom(fields, "amount", "total", "expenseAmount", "totalAmount")
            else -> amountFrom(fields, "amount", "transactionAmount", "contribution", "depositAmount", "withdrawalAmount")
        }.let { abs(it) }

        val rawType = stringFrom(fields, "transactionType", "movementType", "type", "action", "direction").lowercase(Locale.ROOT)
        val withdrawal = type == "saving" && (rawType.contains("withdraw") ||
                amountFrom(fields, "amount", "transactionAmount") < 0.0)

        val category = when (type) {
            "expense" -> stringFrom(fields, "category", "expenseCategory", "type", "group")
            "saving" -> stringFrom(fields, "category", "goalName", "accountName", "savingsName")
            else -> stringFrom(fields, "incomeSource", "salaryType", "source", "employer", "category")
        }

        val title = when (type) {
            "salary" -> stringFrom(fields, "description", "title", "name", "incomeSource", "employer", "salaryType").ifBlank { "Salary / Income" }
            "expense" -> stringFrom(fields, "description", "title", "name", "merchant", "payee").ifBlank { category.ifBlank { "Expense" } }
            else -> stringFrom(fields, "description", "title", "name", "goalName", "accountName").ifBlank {
                if (withdrawal) "Savings withdrawal" else "Savings contribution"
            }
        }
        val detail = when (type) {
            "expense" -> category
            "saving" -> if (withdrawal) "Withdrawal" else stringFrom(fields, "transactionType", "type").ifBlank { "Contribution" }
            else -> stringFrom(fields, "payPeriod", "period", "notes")
        }

        return ReportItem(
            id = document.id,
            title = title,
            category = category,
            amount = amount,
            date = date,
            detail = detail,
            isWithdrawal = withdrawal
        )
    }

    private fun toPaymentRecord(document: CachedDocument, date: Long): PaymentRecord {
        val fields = document.fields
        val amount = abs(amountFrom(fields, "amount", "paymentAmount", "paidAmount", "total"))
        val source = stringFrom(fields, "source", "sourceType", "paymentType", "type").lowercase(Locale.ROOT)
        val loanId = stringFrom(fields, "loanId")
        val principalKeys = listOf("principal", "principalPaid", "principalAmount")
        val interestKeys = listOf("interest", "interestPaid", "interestAmount")
        val feesKeys = listOf("fees", "feesPaid", "feeAmount")
        val hasAllocation = fields.keys.any { it in principalKeys + interestKeys + feesKeys }

        var principal = amountFrom(fields, *principalKeys.toTypedArray()).coerceAtLeast(0.0)
        var interest = amountFrom(fields, *interestKeys.toTypedArray()).coerceAtLeast(0.0)
        var fees = amountFrom(fields, *feesKeys.toTypedArray()).coerceAtLeast(0.0)

        // The Loans module records loan instalments as a single payment amount.
        // When no allocation is stored, split that payment proportionally using
        // the saved loan principal, interest and fee totals. This is an estimate.
        if (!hasAllocation && (source.contains("loan") || loanId.isNotBlank())) {
            val loan = cachedCollections["loans"].orEmpty().firstOrNull { it.id == loanId }
            if (loan != null) {
                val loanPrincipal = amountFrom(loan.fields, "principal", "principalAmount", "amountBorrowed")
                val loanInterest = amountFrom(loan.fields, "totalInterest", "interestAmount", "interest")
                val loanFees = amountFrom(loan.fields, "fees", "additionalFees")
                val total = amountFrom(loan.fields, "totalAmount").takeIf { it > 0.0 }
                    ?: (loanPrincipal + loanInterest + loanFees)
                if (total > 0.0) {
                    principal = amount * loanPrincipal / total
                    interest = amount * loanInterest / total
                    fees = amount * loanFees / total
                }
            }
        }

        val description = stringFrom(
            fields,
            "description",
            "obligationName",
            "loanName",
            "title",
            "payee",
            "provider",
            "category"
        ).ifBlank { "Payment" }
        val method = stringFrom(fields, "method", "paymentMethod", "mode")
        val reference = stringFrom(fields, "reference", "referenceNumber", "transactionReference")

        return PaymentRecord(
            id = document.id,
            obligationId = stringFrom(fields, "obligationId"),
            loanId = loanId,
            description = description,
            amount = amount,
            date = date,
            method = method.ifBlank { source.replace('_', ' ').replaceFirstChar { it.uppercase() } },
            principal = principal,
            interest = interest,
            fees = fees,
            reference = reference,
            source = source
        )
    }

    // -------------------------------------------------------------------------
    // SUMMARY / CHARTS / CATEGORY BREAKDOWN
    // -------------------------------------------------------------------------

    private fun updateFinancialSummary(snapshot: ReportSnapshot) {
        findViewById<TextView>(R.id.tvReportIncome).text = formatMoney(snapshot.income)
        findViewById<TextView>(R.id.tvReportExpenses).text = formatMoney(snapshot.expenseTotal)
        findViewById<TextView>(R.id.tvReportSavings).text = formatMoney(snapshot.contributions)
        findViewById<TextView>(R.id.tvReportPayments).text = formatMoney(snapshot.paymentTotal)

        val cashFlowView = findViewById<TextView>(R.id.tvReportNetCashFlow)
        cashFlowView.text = formatMoney(snapshot.netCashFlow)
        cashFlowView.setTextColor(
            if (snapshot.netCashFlow >= 0.0) Color.parseColor("#149B71")
            else Color.parseColor("#D85D68")
        )
        findViewById<TextView>(R.id.tvReportNetCashDescription).text =
            "Income less expenses, bill/loan payments and net savings added."

        findViewById<TextView>(R.id.tvReportSavingsContributions).text = formatMoney(snapshot.contributions)
        findViewById<TextView>(R.id.tvReportPrincipalRepaid).text = formatMoney(snapshot.principalRepaid)
        findViewById<TextView>(R.id.tvReportInterestPaid).text = formatMoney(snapshot.interestPaid)
        findViewById<TextView>(R.id.tvReportFeesPaid).text = formatMoney(snapshot.feesPaid)
        findViewById<TextView>(R.id.tvReportOutstandingDebt).text = formatMoney(snapshot.outstandingDebt)

        // Preserve an honest distinction between loans and unpaid bills in the export note.
        findViewById<TextView>(R.id.tvReportExportStatus).text =
            "Outstanding loans: ${formatMoney(snapshot.outstandingDebt)} · Unpaid scheduled bills: ${formatMoney(snapshot.unpaidObligations)}. Loan-payment breakdowns may be estimated."
    }

    private fun updateCharts(snapshot: ReportSnapshot) {

        // Income vs Expenses Chart
        val incomeFrame = findViewById<FrameLayout>(
            R.id.chartReportIncomeExpense
        )

        incomeFrame.removeAllViews()

        if (snapshot.income > 0.0 || snapshot.expenseTotal > 0.0) {
            incomeFrame.addView(
                IncomeExpenseChartView(
                    snapshot.income,
                    snapshot.expenseTotal
                ),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            incomeFrame.addView(
                createChartEmptyText("No income or expense records for this period.")
            )
        }

        // Expense Category Chart
        val categoryFrame = findViewById<FrameLayout>(
            R.id.chartReportCategories
        )

        categoryFrame.removeAllViews()

        if (snapshot.categories.isNotEmpty()) {
            categoryFrame.addView(
                CategoryPieChartView(snapshot.categories.take(5)),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            categoryFrame.addView(
                createChartEmptyText("No expense categories found for this period.")
            )
        }
    }

    private fun createChartEmptyText(message: String): TextView {
        return TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#858B9E"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun updateCategoryBreakdown(categories: List<Pair<String, Double>>) {
        val container = findViewById<LinearLayout>(R.id.reportCategoryBreakdown)
        val empty = findViewById<TextView>(R.id.tvReportCategoryEmpty)
        container.removeAllViews()

        if (categories.isEmpty()) {
            empty.text = "No expense categories recorded for this period."
            empty.visibility = View.VISIBLE
            container.addView(empty)
            return
        }

        empty.visibility = View.GONE
        val total = categories.sumOf { it.second }.coerceAtLeast(0.01)
        categories.take(12).forEach { (name, amount) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = makeText(name, 11f, "#50556B", false)
            top.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(makeText("${formatMoney(amount)}  ·  ${(amount / total * 100.0).roundToInt()}%", 10f, "#202334", true))
            row.addView(top)

            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = (amount / total * 100.0).roundToInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6558D3"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ECEAF6"))
            }
            row.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(6)
            })
            container.addView(row)
        }
    }

    // -------------------------------------------------------------------------
    // PAYDAY-TO-PAYDAY BREAKDOWN
    // -------------------------------------------------------------------------

    private fun calculatePaydayPeriod(firstPayday: Boolean): PaydayTotals {
        val firstDay = intFrom(profileFields, "paydayDayOne").takeIf { it in 1..31 } ?: 15
        val rawSecondDay = intFrom(profileFields, "paydayDayTwo").takeIf { it in 0..31 } ?: 30
        val targetYear = reportCalendar.get(Calendar.YEAR)
        val targetMonth = reportCalendar.get(Calendar.MONTH)
        val monthCount = if (annualMode) 12 else 1

        var incomeTotal = 0.0
        var expenseTotal = 0.0
        val allSalaries = cachedCollections["salaries"].orEmpty().mapNotNull { document ->
            val date = dateFrom(
                document.fields,
                listOf("salaryDate", "salaryDateMillis", "paydayDate", "payDate", "paymentDate", "dateMillis", "date", "dateString", "createdAt")
            ) ?: return@mapNotNull null
            val amount = abs(amountFrom(
                document.fields,
                "netSalary", "netAmount", "netPay", "takeHomePay", "salaryAmount", "amount", "grossSalary"
            ))
            Calendar.getInstance(timezone).apply { timeInMillis = date } to amount
        }
        val allExpenses = cachedCollections["expenses"].orEmpty().mapNotNull { document ->
            val date = dateFrom(document.fields, listOf("dateMillis", "expenseDateMillis", "date", "transactionDate", "dateString", "createdAt"))
                ?: return@mapNotNull null
            val amount = abs(amountFrom(document.fields, "amount", "total", "expenseAmount", "totalAmount"))
            Calendar.getInstance(timezone).apply { timeInMillis = date } to amount
        }

        for (offset in 0 until monthCount) {
            val targetMonthCalendar = Calendar.getInstance(timezone).apply {
                clear()
                set(targetYear, if (annualMode) offset else targetMonth, 1, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val year = targetMonthCalendar.get(Calendar.YEAR)
            val month = targetMonthCalendar.get(Calendar.MONTH)
            val maxDay = targetMonthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            val firstPaydayActual = firstDay.coerceIn(1, maxDay)
            val secondPaydayActual = if (rawSecondDay == 0 || rawSecondDay > maxDay) maxDay else rawSecondDay.coerceIn(1, maxDay)

            if (firstPayday) {
                incomeTotal += allSalaries.filter { (date, _) ->
                    date.get(Calendar.YEAR) == year && date.get(Calendar.MONTH) == month &&
                            date.get(Calendar.DAY_OF_MONTH) == firstPaydayActual
                }.sumOf { it.second }

                expenseTotal += allExpenses.filter { (date, _) ->
                    date.get(Calendar.YEAR) == year && date.get(Calendar.MONTH) == month &&
                            date.get(Calendar.DAY_OF_MONTH) >= firstPaydayActual
                }.sumOf { it.second }
            } else {
                val previousMonth = (targetMonthCalendar.clone() as Calendar).apply {
                    add(Calendar.MONTH, -1)
                }
                val previousMaxDay = previousMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
                val previousSecondDay = if (rawSecondDay == 0 || rawSecondDay > previousMaxDay) {
                    previousMaxDay
                } else {
                    rawSecondDay.coerceIn(1, previousMaxDay)
                }

                incomeTotal += allSalaries.filter { (date, _) ->
                    date.get(Calendar.YEAR) == previousMonth.get(Calendar.YEAR) &&
                            date.get(Calendar.MONTH) == previousMonth.get(Calendar.MONTH) &&
                            date.get(Calendar.DAY_OF_MONTH) == previousSecondDay
                }.sumOf { it.second }

                expenseTotal += allExpenses.filter { (date, _) ->
                    date.get(Calendar.YEAR) == year && date.get(Calendar.MONTH) == month &&
                            date.get(Calendar.DAY_OF_MONTH) < firstPaydayActual
                }.sumOf { it.second }
            }
        }
        return PaydayTotals(incomeTotal, expenseTotal)
    }

    private fun updatePaydayBreakdown(first: PaydayTotals, second: PaydayTotals) {
        val firstDay = intFrom(profileFields, "paydayDayOne").takeIf { it in 1..31 } ?: 15
        val rawSecondDay = intFrom(profileFields, "paydayDayTwo").takeIf { it in 0..31 } ?: 30
        val secondTitle = if (rawSecondDay == 0 || rawSecondDay >= 30) {
            "MONTH-END SALARY PERIOD"
        } else {
            "${rawSecondDay}${ordinalSuffix(rawSecondDay)} SALARY PERIOD"
        }
        findViewById<TextView>(R.id.tvReportFirstPaydayTitle).text =
            "${firstDay}${ordinalSuffix(firstDay)} SALARY PERIOD"
        findViewById<TextView>(R.id.tvReportFirstPaydayDescription).text =
            "Covers the ${firstDay}${ordinalSuffix(firstDay)} through month-end."
        findViewById<TextView>(R.id.tvReportSecondPaydayTitle).text = secondTitle
        findViewById<TextView>(R.id.tvReportSecondPaydayDescription).text =
            if (firstDay > 1) "Covers the 1st–${firstDay - 1}${ordinalSuffix(firstDay - 1)} of the selected month." else "Covers the start of the selected month."

        findViewById<TextView>(R.id.tvReportFirstPaydayIncome).text = formatMoney(first.income)
        findViewById<TextView>(R.id.tvReportFirstPaydayExpenses).text = formatMoney(first.expenses)
        findViewById<TextView>(R.id.tvReportFirstPaydayRemaining).apply {
            text = formatMoney(first.remaining)
            setTextColor(if (first.remaining >= 0.0) Color.parseColor("#6558D3") else Color.parseColor("#D85D68"))
        }
        findViewById<TextView>(R.id.tvReportSecondPaydayIncome).text = formatMoney(second.income)
        findViewById<TextView>(R.id.tvReportSecondPaydayExpenses).text = formatMoney(second.expenses)
        findViewById<TextView>(R.id.tvReportSecondPaydayRemaining).apply {
            text = formatMoney(second.remaining)
            setTextColor(if (second.remaining >= 0.0) Color.parseColor("#149B71") else Color.parseColor("#D85D68"))
        }
    }

    private fun ordinalSuffix(day: Int): String = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    // -------------------------------------------------------------------------
    // FINANCIAL RECORD LISTS
    // -------------------------------------------------------------------------

    private fun updateFinancialDetails(snapshot: ReportSnapshot) {
        findViewById<TextView>(R.id.tvReportSalaryCount).text = "${snapshot.salaries.size} records"
        findViewById<TextView>(R.id.tvReportExpenseCount).text = "${snapshot.expenses.size} records"
        findViewById<TextView>(R.id.tvReportPaymentCount).text = "${snapshot.payments.size} records"
        findViewById<TextView>(R.id.tvReportSavingCount).text = "${snapshot.savings.size} records"

        updateSimpleRecordList(
            R.id.reportSalaryList,
            R.id.tvReportSalaryEmpty,
            snapshot.salaries,
            "No salary records for this period."
        )
        updateSimpleRecordList(
            R.id.reportExpenseList,
            R.id.tvReportExpenseEmpty,
            snapshot.expenses,
            "No expense records for this period."
        )
        updatePaymentList(snapshot.payments)
        updateSimpleRecordList(
            R.id.reportSavingList,
            R.id.tvReportSavingEmpty,
            snapshot.savings,
            "No savings records for this period."
        )
    }

    private fun updateSimpleRecordList(
        containerId: Int,
        emptyId: Int,
        items: List<ReportItem>,
        emptyMessage: String
    ) {
        val container = findViewById<LinearLayout>(containerId)
        val empty = findViewById<TextView>(emptyId)
        container.removeAllViews()
        if (items.isEmpty()) {
            empty.text = emptyMessage
            empty.visibility = View.VISIBLE
            container.addView(empty)
            return
        }
        empty.visibility = View.GONE
        items.sortedByDescending { it.date }.take(100).forEach { item ->
            container.addView(createRecordRow(item))
        }
        if (items.size > 100) {
            container.addView(makeText("Showing the latest 100 of ${items.size} records.", 10f, "#858B9E", false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
        }
    }

    private fun createRecordRow(item: ReportItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = roundedBackground("#F8F8FC")
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(makeText(item.title, 12f, "#202334", true))
        val subline = buildString {
            append(formatDate(item.date))
            if (item.detail.isNotBlank()) append(" · ${item.detail}")
        }
        left.addView(makeText(subline, 10f, "#858B9E", false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val amountText = if (item.isWithdrawal) "−${formatMoney(item.amount)}" else formatMoney(item.amount)
        val amountColor = if (item.isWithdrawal) "#D85D68" else when {
            item.category.isNotBlank() && item.detail.contains("withdraw", true) -> "#D85D68"
            else -> "#202334"
        }
        top.addView(makeText(amountText, 12f, amountColor, true))
        row.addView(top)
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
    }

    private fun updatePaymentList(records: List<PaymentRecord>) {
        val container = findViewById<LinearLayout>(R.id.reportPaymentList)
        val empty = findViewById<TextView>(R.id.tvReportPaymentEmpty)
        container.removeAllViews()
        if (records.isEmpty()) {
            empty.text = "No payment records for this period."
            empty.visibility = View.VISIBLE
            container.addView(empty)
            return
        }
        empty.visibility = View.GONE
        records.sortedByDescending { it.date }.take(100).forEach { record ->
            container.addView(createPaymentRow(record))
        }
        if (records.size > 100) {
            container.addView(makeText("Showing the latest 100 of ${records.size} records.", 10f, "#858B9E", false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(4))
            })
        }
    }

    private fun createPaymentRow(record: PaymentRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = roundedBackground("#F8F8FC")
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(makeText(record.description.ifBlank { "Payment" }, 12f, "#202334", true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(makeText(formatMoney(record.amount), 13f, "#149B71", true))
        row.addView(top)

        val details = buildString {
            append(formatDate(record.date))
            if (record.method.isNotBlank()) append(" · ${record.method}")
            if (record.source.isNotBlank()) append(" · ${record.source.replace('_', ' ')}")
        }
        row.addView(makeText(details, 10f, "#858B9E", false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        if (record.principal > 0.0 || record.interest > 0.0 || record.fees > 0.0) {
            row.addView(makeText(
                "Principal ${formatMoney(record.principal)} · Interest ${formatMoney(record.interest)} · Fees ${formatMoney(record.fees)}",
                9f,
                "#858B9E",
                false
            ).apply { setPadding(0, dp(5), 0, 0) })
        }
        if (record.reference.isNotBlank()) {
            row.addView(makeText("Reference: ${record.reference}", 9f, "#858B9E", false).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
    }

    // -------------------------------------------------------------------------
    // LOANS / OBLIGATIONS TOTALS
    // -------------------------------------------------------------------------

    private fun calculateOutstandingLoanDebt(): Double {
        return cachedCollections["loans"].orEmpty().sumOf { loan ->
            val fields = loan.fields
            val status = stringFrom(fields, "status").lowercase(Locale.ROOT)
            val totalAmount = amountFrom(fields, "totalAmount").takeIf { it > 0.0 } ?: (
                    amountFrom(fields, "principal", "principalAmount", "amountBorrowed", "amount") +
                            amountFrom(fields, "totalInterest", "interestAmount", "interest") +
                            amountFrom(fields, "fees", "additionalFees")
                    )
            val paid = amountFrom(fields, "totalPaid", "amountPaid", "paidAmount")
            val explicitBalance = explicitAmountOrNull(
                fields,
                "remainingBalance",
                "outstandingBalance",
                "currentBalance",
                "remainingAmount"
            )
            val balance = explicitBalance?.coerceAtLeast(0.0)
                ?: if (status.contains("paid") || status == "closed") 0.0
                else (totalAmount - paid).coerceAtLeast(0.0)
            balance
        }
    }

    private fun calculateUnpaidObligations(): Double {
        return cachedCollections["obligations"].orEmpty().sumOf { obligation ->
            val fields = obligation.fields
            val status = stringFrom(fields, "status").lowercase(Locale.ROOT)
            if (status in setOf("cancelled", "canceled", "rejected", "closed")) return@sumOf 0.0
            val explicit = explicitAmountOrNull(fields, "remainingBalance", "outstandingAmount", "unpaidAmount")
            (explicit ?: (
                    amountFrom(fields, "amount", "totalAmount") -
                            amountFrom(fields, "paidAmount", "amountPaid")
                    )).coerceAtLeast(0.0)
        }
    }

    // -------------------------------------------------------------------------
    // CSV EXPORT
    // -------------------------------------------------------------------------

    private fun exportCsv() {
        val snapshot = currentSnapshot ?: buildReportSnapshot().also { currentSnapshot = it }
        val output = StringBuilder()
        output.appendLine("FINANCIAL REPORT")
        output.appendLine("Report Type,${csvEscape(if (annualMode) "Annual" else "Monthly")}")
        output.appendLine("Period,${csvEscape(selectedPeriodLabel())}")
        output.appendLine()
        output.appendLine("FINANCIAL SUMMARY")
        output.appendLine("Income,${snapshot.income}")
        output.appendLine("Expenses,${snapshot.expenseTotal}")
        output.appendLine("Savings Contributions,${snapshot.contributions}")
        output.appendLine("Savings Withdrawals,${snapshot.withdrawals}")
        output.appendLine("Payments,${snapshot.paymentTotal}")
        output.appendLine("Net Cash Flow,${snapshot.netCashFlow}")
        output.appendLine("Loan Principal Repaid,${snapshot.principalRepaid}")
        output.appendLine("Loan Interest Paid,${snapshot.interestPaid}")
        output.appendLine("Fees Paid,${snapshot.feesPaid}")
        output.appendLine("Outstanding Loan Debt,${snapshot.outstandingDebt}")
        output.appendLine("Unpaid Scheduled Bills,${snapshot.unpaidObligations}")
        output.appendLine()

        output.appendLine("EXPENSES BY CATEGORY")
        output.appendLine("Category,Amount")
        snapshot.categories.forEach { (category, amount) ->
            output.appendLine("${csvEscape(category)},$amount")
        }
        output.appendLine()

        output.appendLine("15TH PAYDAY PERIOD (15TH THROUGH MONTH-END)")
        output.appendLine("Income,${snapshot.firstPayday.income}")
        output.appendLine("Expenses,${snapshot.firstPayday.expenses}")
        output.appendLine("Remaining,${snapshot.firstPayday.remaining}")
        output.appendLine()
        output.appendLine("MONTH-END PAYDAY PERIOD (1ST THROUGH 14TH)")
        output.appendLine("Income,${snapshot.secondPayday.income}")
        output.appendLine("Expenses,${snapshot.secondPayday.expenses}")
        output.appendLine("Remaining,${snapshot.secondPayday.remaining}")
        output.appendLine()

        appendItemsCsv(output, "SALARY RECORDS", snapshot.salaries)
        appendItemsCsv(output, "EXPENSE RECORDS", snapshot.expenses)
        appendPaymentCsv(output, snapshot.payments)
        appendItemsCsv(output, "SAVINGS RECORDS", snapshot.savings)

        currentCsv = "\uFEFF$output"
        val fileName = if (annualMode) {
            "Financial_Report_${reportCalendar.get(Calendar.YEAR)}.csv"
        } else {
            "Financial_Report_${SimpleDateFormat("yyyy_MM", Locale.US).format(reportCalendar.time)}.csv"
        }
        csvLauncher.launch(fileName)
    }

    private fun appendItemsCsv(output: StringBuilder, heading: String, items: List<ReportItem>) {
        output.appendLine(heading)
        output.appendLine("Date,Description,Category,Amount,Direction")
        items.sortedBy { it.date }.forEach { item ->
            output.appendLine(listOf(
                csvEscape(formatDate(item.date)),
                csvEscape(item.title),
                csvEscape(item.category),
                item.amount.toString(),
                when {
                    item.isWithdrawal -> "Withdrawal"
                    heading.contains("EXPENSE", ignoreCase = true) -> "Expense"
                    heading.contains("SALARY", ignoreCase = true) -> "Income"
                    else -> "Contribution"
                }
            ).joinToString(","))
        }
        output.appendLine()
    }

    private fun appendPaymentCsv(output: StringBuilder, records: List<PaymentRecord>) {
        output.appendLine("PAYMENT RECORDS")
        output.appendLine("Date,Description,Method,Source,Amount,Principal,Interest,Fees,Reference")
        records.sortedBy { it.date }.forEach { record ->
            output.appendLine(listOf(
                csvEscape(formatDate(record.date)),
                csvEscape(record.description),
                csvEscape(record.method),
                csvEscape(record.source),
                record.amount.toString(),
                record.principal.toString(),
                record.interest.toString(),
                record.fees.toString(),
                csvEscape(record.reference)
            ).joinToString(","))
        }
        output.appendLine()
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    // -------------------------------------------------------------------------
    // PDF / PRINT
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun printReport() {
        val snapshot = currentSnapshot ?: buildReportSnapshot().also { currentSnapshot = it }
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                try {
                    val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                    val adapter = webView.createPrintDocumentAdapter("Financial_Report")
                    printManager.print(
                        "Money Tracker Financial Report",
                        adapter,
                        PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()
                    )
                } catch (exception: Exception) {
                    showError("Unable to open print preview: ${exception.message ?: "Unknown error"}")
                }
            }
        }
        webView.loadDataWithBaseURL(null, buildReportHtml(snapshot), "text/html", "UTF-8", null)
    }

    private fun buildReportHtml(snapshot: ReportSnapshot): String {
        val period = htmlEscape(selectedPeriodLabel())
        fun rows(items: List<ReportItem>): String = items.sortedByDescending { it.date }.joinToString("") { item ->
            "<tr><td>${htmlEscape(formatDate(item.date))}</td><td>${htmlEscape(item.title)}</td><td>${htmlEscape(item.category)}</td><td>${htmlEscape(formatMoney(item.amount))}</td></tr>"
        }
        val paymentRows = snapshot.payments.sortedByDescending { it.date }.joinToString("") { payment ->
            "<tr><td>${htmlEscape(formatDate(payment.date))}</td><td>${htmlEscape(payment.description)}</td><td>${htmlEscape(payment.method)}</td><td>${htmlEscape(payment.source)}</td><td>${htmlEscape(formatMoney(payment.amount))}</td></tr>"
        }
        val categoryRows = snapshot.categories.joinToString("") { (category, amount) ->
            "<tr><td>${htmlEscape(category)}</td><td>${htmlEscape(formatMoney(amount))}</td></tr>"
        }

        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <style>
                body{font-family:Arial,sans-serif;padding:24px;color:#202334;font-size:12px}
                h1{font-size:23px;margin:0 0 5px} h2{font-size:15px;margin:24px 0 8px}
                .muted{color:#858B9E;margin-bottom:20px}
                .summary{background:#F2F0FF;padding:14px;border-radius:10px;margin:14px 0}
                .grid{display:flex;flex-wrap:wrap;gap:10px}.metric{width:46%;padding:8px 0}
                .metric span{display:block;color:#858B9E;font-size:10px}.metric strong{font-size:14px}
                table{width:100%;border-collapse:collapse;margin:8px 0 18px}
                th,td{padding:8px 6px;border-bottom:1px solid #E5E7EB;text-align:left;font-size:10px;vertical-align:top}
                th{background:#F5F6FA}.note{color:#858B9E;font-size:9px;margin-top:18px}
            </style></head><body>
                <h1>Money Tracker</h1><div class="muted">Financial Report · $period</div>
                <div class="summary"><h2 style="margin-top:0">Financial Summary</h2><div class="grid">
                    <div class="metric"><span>Income</span><strong>${htmlEscape(formatMoney(snapshot.income))}</strong></div>
                    <div class="metric"><span>Expenses</span><strong>${htmlEscape(formatMoney(snapshot.expenseTotal))}</strong></div>
                    <div class="metric"><span>Savings Contributions</span><strong>${htmlEscape(formatMoney(snapshot.contributions))}</strong></div>
                    <div class="metric"><span>Payments</span><strong>${htmlEscape(formatMoney(snapshot.paymentTotal))}</strong></div>
                    <div class="metric"><span>Net Cash Flow</span><strong>${htmlEscape(formatMoney(snapshot.netCashFlow))}</strong></div>
                    <div class="metric"><span>Outstanding Loan Debt</span><strong>${htmlEscape(formatMoney(snapshot.outstandingDebt))}</strong></div>
                    <div class="metric"><span>Unpaid Scheduled Bills</span><strong>${htmlEscape(formatMoney(snapshot.unpaidObligations))}</strong></div>
                    <div class="metric"><span>Loan Interest / Fees Paid</span><strong>${htmlEscape(formatMoney(snapshot.interestPaid + snapshot.feesPaid))}</strong></div>
                </div></div>
                <h2>15th Payday Period · 15th through month-end</h2>
                <p>Income: <b>${htmlEscape(formatMoney(snapshot.firstPayday.income))}</b> · Expenses: <b>${htmlEscape(formatMoney(snapshot.firstPayday.expenses))}</b> · Remaining: <b>${htmlEscape(formatMoney(snapshot.firstPayday.remaining))}</b></p>
                <h2>Month-end Payday Period · 1st through 14th</h2>
                <p>Income: <b>${htmlEscape(formatMoney(snapshot.secondPayday.income))}</b> · Expenses: <b>${htmlEscape(formatMoney(snapshot.secondPayday.expenses))}</b> · Remaining: <b>${htmlEscape(formatMoney(snapshot.secondPayday.remaining))}</b></p>
                <h2>Expenses by Category</h2><table><thead><tr><th>Category</th><th>Amount</th></tr></thead><tbody>$categoryRows</tbody></table>
                <h2>Salary Records</h2><table><thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Amount</th></tr></thead><tbody>${rows(snapshot.salaries)}</tbody></table>
                <h2>Expense Records</h2><table><thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Amount</th></tr></thead><tbody>${rows(snapshot.expenses)}</tbody></table>
                <h2>Payment Records</h2><table><thead><tr><th>Date</th><th>Description</th><th>Method</th><th>Source</th><th>Amount</th></tr></thead><tbody>$paymentRows</tbody></table>
                <h2>Savings Records</h2><table><thead><tr><th>Date</th><th>Description</th><th>Category</th><th>Amount</th></tr></thead><tbody>${rows(snapshot.savings)}</tbody></table>
                <div class="note">Loan principal, interest and fee allocations for loan payments without an explicit breakdown are estimated proportionally from the saved loan terms. Use lender statements for exact allocation.</div>
            </body></html>
        """.trimIndent()
    }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    // -------------------------------------------------------------------------
    // EMAIL / SHARE
    // -------------------------------------------------------------------------

    private fun emailReport() {
        val snapshot = currentSnapshot ?: buildReportSnapshot().also { currentSnapshot = it }
        val body = buildString {
            appendLine("MONEY TRACKER FINANCIAL REPORT")
            appendLine("Period: ${selectedPeriodLabel()}")
            appendLine()
            appendLine("FINANCIAL SUMMARY")
            appendLine("Income: ${formatMoney(snapshot.income)}")
            appendLine("Expenses: ${formatMoney(snapshot.expenseTotal)}")
            appendLine("Savings contributions: ${formatMoney(snapshot.contributions)}")
            appendLine("Savings withdrawals: ${formatMoney(snapshot.withdrawals)}")
            appendLine("Payments: ${formatMoney(snapshot.paymentTotal)}")
            appendLine("Net cash flow: ${formatMoney(snapshot.netCashFlow)}")
            appendLine("Loan principal repaid: ${formatMoney(snapshot.principalRepaid)}")
            appendLine("Loan interest paid: ${formatMoney(snapshot.interestPaid)}")
            appendLine("Fees paid: ${formatMoney(snapshot.feesPaid)}")
            appendLine("Outstanding loan debt: ${formatMoney(snapshot.outstandingDebt)}")
            appendLine("Unpaid scheduled bills: ${formatMoney(snapshot.unpaidObligations)}")
            appendLine()
            appendLine("EXPENSES BY CATEGORY")
            snapshot.categories.forEach { (category, amount) -> appendLine("$category: ${formatMoney(amount)}") }
            appendLine()
            appendLine("PAYMENT HISTORY")
            snapshot.payments.sortedByDescending { it.date }.forEach { payment ->
                appendLine("${formatDate(payment.date)} | ${payment.description} | ${formatMoney(payment.amount)} | ${payment.method}")
            }
            appendLine()
            appendLine("The report includes available records from your signed-in account.")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Money Tracker Financial Report - ${selectedPeriodLabel()}")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share Financial Report"))
        } catch (exception: Exception) {
            showError("Unable to open a sharing app: ${exception.message ?: "Unknown error"}")
        }
    }

    // -------------------------------------------------------------------------
    // VALUE / DATE HELPERS
    // -------------------------------------------------------------------------

    private fun amountFrom(fields: Map<String, Any?>, vararg names: String): Double {
        for (name in names) {
            if (fields.containsKey(name) && fields[name] != null) return parseAmount(fields[name])
        }
        return 0.0
    }

    private fun explicitAmountOrNull(fields: Map<String, Any?>, vararg names: String): Double? {
        for (name in names) {
            if (fields.containsKey(name) && fields[name] != null) return parseAmount(fields[name])
        }
        return null
    }

    private fun parseAmount(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> {
            val raw = value.trim()
            val negativeParentheses = raw.startsWith("(") && raw.endsWith(")")
            val parsed = raw.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0
            if (negativeParentheses && parsed > 0.0) -parsed else parsed
        }
        else -> 0.0
    }

    private fun stringFrom(fields: Map<String, Any?>, vararg names: String): String {
        for (name in names) {
            val value = fields[name]
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
        }
        return ""
    }

    private fun intFrom(fields: Map<String, Any?>, name: String): Int =
        parseAmount(fields[name]).toInt()

    private fun dateFrom(fields: Map<String, Any?>, names: List<String>): Long? {
        for (name in names) {
            val value = fields[name] ?: continue
            val parsed = when (value) {
                is Timestamp -> value.toDate().time
                is Date -> value.time
                is Number -> {
                    val raw = value.toLong()
                    if (raw <= 0L) null else if (raw < 100_000_000_000L) raw * 1000L else raw
                }
                is String -> parseDateString(value)
                is Map<*, *> -> {
                    val seconds = (value["seconds"] as? Number)?.toLong()
                        ?: (value["_seconds"] as? Number)?.toLong()
                    seconds?.times(1000L)
                }
                else -> null
            }
            if (parsed != null && parsed > 0L) return parsed
        }
        return null
    }

    private fun parseDateString(value: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "MMM d, yyyy",
            "MMMM d, yyyy"
        )
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = timezone
                }
                val date = formatter.parse(value.trim())
                if (date != null) return date.time
            } catch (_: Exception) {
                // Try the next known date format.
            }
        }
        return null
    }

    private fun isClosedStatus(status: String): Boolean = status.lowercase(Locale.ROOT) in setOf(
        "paid", "paid_off", "paid off", "completed", "fully paid", "closed", "cancelled", "canceled", "rejected"
    )

    private fun formatMoney(amount: Double): String = currencyFormatter.format(amount)
    private fun formatDate(timestamp: Long): String = dateFormatter.format(Date(timestamp))
    private fun formatCachedTime(): String = if (lastUpdatedMillis > 0L) {
        dateTimeFormatter.format(Date(lastUpdatedMillis))
    } else {
        "not available"
    }

    private fun roundedBackground(color: String): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(9).toFloat()
        }

    private fun makeText(text: String, sizeSp: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        findViewById<TextView>(R.id.tvReportError).apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    // -------------------------------------------------------------------------
    // NATIVE CHART VIEWS
    // -------------------------------------------------------------------------

    private inner class IncomeExpenseChartView(
        private val income: Double,
        private val expenses: Double
    ) : View(this@ReportsActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val purple = Color.parseColor("#6558D3")
        private val orange = Color.parseColor("#F1AE64")
        private val muted = Color.parseColor("#A0A4B2")
        private val text = Color.parseColor("#687087")

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(28).toFloat()
            val right = width - dp(16).toFloat()
            val top = dp(18).toFloat()
            val bottom = height - dp(36).toFloat()
            val plotHeight = (bottom - top).coerceAtLeast(1f)
            val maxValue = max(max(income, expenses), 1.0)

            paint.color = muted
            paint.strokeWidth = dp(1).toFloat()
            paint.style = Paint.Style.STROKE
            for (line in 0..3) {
                val y = top + plotHeight * line / 3f
                canvas.drawLine(left, y, right, y, paint)
            }

            paint.style = Paint.Style.FILL
            val barWidth = dp(55).toFloat()
            val gap = (right - left) / 2f
            val values = listOf(income to purple, expenses to orange)
            values.forEachIndexed { index, (amount, color) ->
                val centerX = left + gap * (index + 0.5f)
                val barHeight = (amount / maxValue * (plotHeight - dp(16))).toFloat().coerceAtLeast(0f)
                val rect = RectF(
                    centerX - barWidth / 2f,
                    bottom - barHeight,
                    centerX + barWidth / 2f,
                    bottom
                )
                paint.color = color
                canvas.drawRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), paint)

                paint.color = text
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = dp(10).toFloat()
                canvas.drawText(if (index == 0) "Income" else "Expenses", centerX, height - dp(11).toFloat(), paint)
                paint.textSize = dp(9).toFloat()
                canvas.drawText(formatCompact(amount), centerX, (bottom - barHeight - dp(7)).coerceAtLeast(top + dp(9).toFloat()), paint)
            }
        }
    }

    private inner class CategoryPieChartView(
        private val categories: List<Pair<String, Double>>
    ) : View(this@ReportsActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = listOf(
            Color.parseColor("#6558D3"),
            Color.parseColor("#F1AE64"),
            Color.parseColor("#149B71"),
            Color.parseColor("#4285D4"),
            Color.parseColor("#D85D68")
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (categories.isEmpty()) return
            val total = categories.sumOf { it.second }.coerceAtLeast(0.01)
            val centerX = width * 0.27f
            val centerY = height * 0.5f
            val radius = min(height * 0.39f, width * 0.20f).coerceAtLeast(dp(28).toFloat())
            val bounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            var startAngle = -90f

            categories.forEachIndexed { index, item ->
                val sweep = (item.second / total * 360.0).toFloat()
                paint.color = colors[index % colors.size]
                paint.style = Paint.Style.FILL
                canvas.drawArc(bounds, startAngle, sweep, true, paint)
                startAngle += sweep
            }

            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, radius * 0.48f, paint)
            paint.color = Color.parseColor("#202334")
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(10).toFloat()
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("Total", centerX, centerY - dp(2).toFloat(), paint)
            paint.textSize = dp(9).toFloat()
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(formatCompact(total), centerX, centerY + dp(12).toFloat(), paint)

            val legendX = width * 0.53f
            var legendY = dp(29).toFloat()
            categories.forEachIndexed { index, (name, amount) ->
                paint.color = colors[index % colors.size]
                canvas.drawRoundRect(
                    RectF(legendX, legendY - dp(8), legendX + dp(9), legendY + dp(1)),
                    dp(2).toFloat(), dp(2).toFloat(), paint
                )
                paint.color = Color.parseColor("#50556B")
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = dp(9).toFloat()
                val safeName = if (name.length > 18) name.take(17) + "…" else name
                canvas.drawText(safeName, legendX + dp(15), legendY, paint)
                paint.textAlign = Paint.Align.RIGHT
                paint.color = Color.parseColor("#858B9E")
                canvas.drawText(formatCompact(amount), width - dp(10).toFloat(), legendY, paint)
                legendY += dp(27).toFloat()
            }
        }
    }

    private fun formatCompact(amount: Double): String {
        return when {
            amount >= 1_000_000.0 -> "₱${String.format(Locale.US, "%.1fM", amount / 1_000_000.0)}"
            amount >= 10_000.0 -> "₱${String.format(Locale.US, "%.1fK", amount / 1_000.0)}"
            amount >= 1_000.0 -> "₱${String.format(Locale.US, "%.1fK", amount / 1_000.0)}"
            else -> "₱${String.format(Locale.US, "%.0f", amount)}"
        }
    }
}

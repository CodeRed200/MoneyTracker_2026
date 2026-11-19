package com.example.moneytracker

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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

class InsightsActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // FIREBASE AND USER-SCOPED LOCAL CACHE
    // -------------------------------------------------------------------------

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var userId: String

    private val timezone = TimeZone.getTimeZone("Asia/Manila")
    private val collectionNames = listOf(
        "salaries",
        "expenses",
        "savings",
        "payments",
        "loans",
        "obligations",
        "savingsGoals"
    )

    private data class CachedDocument(
        val id: String,
        val fields: Map<String, Any?>
    )

    private data class AlertItem(
        val title: String,
        val message: String,
        val tone: String = "info"
    )

    private val cachedCollections = mutableMapOf<String, List<CachedDocument>>()
    private var profileFields: Map<String, Any?> = emptyMap()
    private var lastUpdated: Date? = null
    private var loadGeneration = 0
    private var isLoading = false

    private val cachePreferences by lazy {
        getSharedPreferences("insights_cache_$userId", MODE_PRIVATE)
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private lateinit var tvInsightsPeriod: TextView
    private lateinit var tvFinancialStatusTitle: TextView
    private lateinit var tvFinancialStatusDescription: TextView
    private lateinit var tvInsightsIncome: TextView
    private lateinit var tvInsightsExpenses: TextView
    private lateinit var tvInsightsNetCashFlow: TextView
    private lateinit var tvInsightsAlertCount: TextView
    private lateinit var tvInsightsAlertsEmpty: TextView
    private lateinit var insightsAlertsList: LinearLayout
    private lateinit var insightsCategoryContainer: LinearLayout
    private lateinit var tvInsightsCategoryEmpty: TextView
    private lateinit var tvAverageDailySpending: TextView
    private lateinit var tvLargestRecordedExpense: TextView
    private lateinit var tvLargestExpenseDescription: TextView
    private lateinit var tvInsightsSavingsRate: TextView
    private lateinit var progressInsightsSavings: ProgressBar
    private lateinit var insightsBudgetContainer: LinearLayout
    private lateinit var tvInsightsBudgetEmpty: TextView
    private lateinit var tvInsightsNextPayday: TextView
    private lateinit var tvInsightsUpcomingObligations: TextView
    private lateinit var tvInsightsProjectedCash: TextView
    private lateinit var tvInsightsPaydayAdvice: TextView
    private lateinit var insightsSavingsContainer: LinearLayout
    private lateinit var tvInsightsSavingsEmpty: TextView
    private lateinit var tvInsightsOutstandingDebt: TextView
    private lateinit var tvInsightsDebtRepaid: TextView
    private lateinit var progressInsightsDebt: ProgressBar
    private lateinit var tvInsightsDebtProgress: TextView
    private lateinit var tvInsightsDebtAdvice: TextView
    private lateinit var tvInsightsRecommendationCount: TextView
    private lateinit var tvInsightsRecommendationsEmpty: TextView
    private lateinit var insightsRecommendationContainer: LinearLayout
    private lateinit var insightsLoading: View
    private lateinit var tvInsightsError: TextView
    private lateinit var tvInsightsLastUpdated: TextView

    // -------------------------------------------------------------------------
    // PERIOD AND COLORS
    // -------------------------------------------------------------------------

    private val selectedPeriod = Calendar.getInstance(timezone)
    private val purple = Color.rgb(101, 88, 211)
    private val purpleLight = Color.rgb(239, 237, 255)
    private val green = Color.rgb(20, 155, 113)
    private val greenLight = Color.rgb(231, 247, 240)
    private val red = Color.rgb(216, 93, 104)
    private val redLight = Color.rgb(255, 241, 241)
    private val orange = Color.rgb(213, 138, 54)
    private val orangeLight = Color.rgb(255, 245, 233)
    private val darkText = Color.rgb(32, 35, 52)
    private val mutedText = Color.rgb(133, 139, 158)
    private val border = Color.rgb(235, 237, 243)

    private val currencyFormatter: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))
    }

    // -------------------------------------------------------------------------
    // ACTIVITY
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_insights)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        setupClickListeners()
        updatePeriodDisplay()
        checkUserSession()
    }

    private fun initializeViews() {
        tvInsightsPeriod = findViewById(R.id.tvInsightsPeriod)
        tvFinancialStatusTitle = findViewById(R.id.tvFinancialStatusTitle)
        tvFinancialStatusDescription = findViewById(R.id.tvFinancialStatusDescription)
        tvInsightsIncome = findViewById(R.id.tvInsightsIncome)
        tvInsightsExpenses = findViewById(R.id.tvInsightsExpenses)
        tvInsightsNetCashFlow = findViewById(R.id.tvInsightsNetCashFlow)
        tvInsightsAlertCount = findViewById(R.id.tvInsightsAlertCount)
        tvInsightsAlertsEmpty = findViewById(R.id.tvInsightsAlertsEmpty)
        insightsAlertsList = findViewById(R.id.insightsAlertsList)
        insightsCategoryContainer = findViewById(R.id.insightsCategoryContainer)
        tvInsightsCategoryEmpty = findViewById(R.id.tvInsightsCategoryEmpty)
        tvAverageDailySpending = findViewById(R.id.tvAverageDailySpending)
        tvLargestRecordedExpense = findViewById(R.id.tvLargestRecordedExpense)
        tvLargestExpenseDescription = findViewById(R.id.tvLargestExpenseDescription)
        tvInsightsSavingsRate = findViewById(R.id.tvInsightsSavingsRate)
        progressInsightsSavings = findViewById(R.id.progressInsightsSavings)
        insightsBudgetContainer = findViewById(R.id.insightsBudgetContainer)
        tvInsightsBudgetEmpty = findViewById(R.id.tvInsightsBudgetEmpty)
        tvInsightsNextPayday = findViewById(R.id.tvInsightsNextPayday)
        tvInsightsUpcomingObligations = findViewById(R.id.tvInsightsUpcomingObligations)
        tvInsightsProjectedCash = findViewById(R.id.tvInsightsProjectedCash)
        tvInsightsPaydayAdvice = findViewById(R.id.tvInsightsPaydayAdvice)
        insightsSavingsContainer = findViewById(R.id.insightsSavingsContainer)
        tvInsightsSavingsEmpty = findViewById(R.id.tvInsightsSavingsEmpty)
        tvInsightsOutstandingDebt = findViewById(R.id.tvInsightsOutstandingDebt)
        tvInsightsDebtRepaid = findViewById(R.id.tvInsightsDebtRepaid)
        progressInsightsDebt = findViewById(R.id.progressInsightsDebt)
        tvInsightsDebtProgress = findViewById(R.id.tvInsightsDebtProgress)
        tvInsightsDebtAdvice = findViewById(R.id.tvInsightsDebtAdvice)
        tvInsightsRecommendationCount = findViewById(R.id.tvInsightsRecommendationCount)
        tvInsightsRecommendationsEmpty = findViewById(R.id.tvInsightsRecommendationsEmpty)
        insightsRecommendationContainer = findViewById(R.id.insightsRecommendationContainer)
        insightsLoading = findViewById(R.id.insightsLoading)
        tvInsightsError = findViewById(R.id.tvInsightsError)
        tvInsightsLastUpdated = findViewById(R.id.tvInsightsLastUpdated)
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.main)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnInsightsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRefreshInsights).setOnClickListener { refreshInsights() }
        findViewById<View>(R.id.btnInsightsChangePeriod).setOnClickListener { showPeriodPicker() }
        findViewById<View>(R.id.tvInsightsPeriod).setOnClickListener { showPeriodPicker() }
        findViewById<View>(R.id.btnOpenInsightsAnalytics).setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
    }

    // -------------------------------------------------------------------------
    // AUTHENTICATION
    // -------------------------------------------------------------------------

    private fun checkUserSession() {
        val user = auth.currentUser
        if (user == null) {
            navigateTo(Login::class.java)
            return
        }
        if (!user.isEmailVerified) {
            navigateTo(EmailVerificationActivity::class.java)
            return
        }
        userId = user.uid
        loadLocalCache()
        if (hasAnyCachedData()) {
            renderInsights()
            tvInsightsLastUpdated.text = "Showing saved data from this device"
        }
        loadInsightsFromFirebase()
    }

    private fun navigateTo(target: Class<*>) {
        val intent = Intent(this, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // -------------------------------------------------------------------------
    // PERIOD PICKER
    // -------------------------------------------------------------------------

    private fun showPeriodPicker() {
        DatePickerDialog(
            this,
            { _, year, month, _ ->
                selectedPeriod.set(year, month, 1, 12, 0, 0)
                selectedPeriod.set(Calendar.MILLISECOND, 0)
                updatePeriodDisplay()
                renderInsights()
            },
            selectedPeriod.get(Calendar.YEAR),
            selectedPeriod.get(Calendar.MONTH),
            1
        ).show()
    }

    private fun updatePeriodDisplay() {
        tvInsightsPeriod.text = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
            timeZone = timezone
        }.format(selectedPeriod.time)
    }

    private fun periodStart(): Date = (selectedPeriod.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun periodEnd(): Date = (selectedPeriod.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time

    private fun isInSelectedPeriod(date: Date): Boolean = !date.before(periodStart()) && !date.after(periodEnd())

    // -------------------------------------------------------------------------
    // LOCAL CACHE
    // -------------------------------------------------------------------------

    private fun loadLocalCache() {
        cachedCollections.clear()
        collectionNames.forEach { name ->
            val raw = cachePreferences.getString("collection_$name", null) ?: return@forEach
            try {
                val array = JSONArray(raw)
                val docs = mutableListOf<CachedDocument>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val fields = item.optJSONObject("fields")?.let { jsonToMap(it) } ?: emptyMap()
                    docs.add(CachedDocument(item.optString("id"), fields))
                }
                cachedCollections[name] = docs
            } catch (exception: Exception) {
                android.util.Log.e("InsightsActivity", "Could not read $name cache", exception)
            }
        }

        cachePreferences.getString("profile", null)?.let { raw ->
            try {
                profileFields = jsonToMap(JSONObject(raw))
            } catch (exception: Exception) {
                android.util.Log.e("InsightsActivity", "Could not read profile cache", exception)
            }
        }
        val savedTime = cachePreferences.getLong("last_updated", 0L)
        if (savedTime > 0L) lastUpdated = Date(savedTime)
    }

    private fun hasAnyCachedData(): Boolean =
        cachedCollections.isNotEmpty() || profileFields.isNotEmpty()

    private fun saveCollectionCache(name: String, documents: List<CachedDocument>) {
        val array = JSONArray()
        documents.forEach { document ->
            array.put(JSONObject().apply {
                put("id", document.id)
                put("fields", mapToJson(document.fields))
            })
        }
        cachePreferences.edit().putString("collection_$name", array.toString()).apply()
    }

    private fun saveProfileCache(fields: Map<String, Any?>) {
        cachePreferences.edit().putString("profile", mapToJson(fields).toString()).apply()
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, valueToJson(value)) }
        return json
    }

    private fun valueToJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Timestamp -> value.toDate().time
        is Date -> value.time
        is Number, is String, is Boolean -> value
        is Map<*, *> -> {
            val nested = JSONObject()
            value.forEach { (key, item) -> if (key is String) nested.put(key, valueToJson(item)) }
            nested
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(valueToJson(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(valueToJson(it)) } }
        else -> value.toString()
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValue(json.opt(key))
        }
        return result
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        else -> value
    }

    private fun toCachedDocument(document: DocumentSnapshot): CachedDocument =
        CachedDocument(document.id, document.data ?: emptyMap())

    // -------------------------------------------------------------------------
    // FIREBASE LOAD AND MERGE WITH LOCAL CACHE
    // -------------------------------------------------------------------------

    private fun refreshInsights() {
        if (!::userId.isInitialized) return
        loadInsightsFromFirebase()
    }

    private fun loadInsightsFromFirebase() {
        val user = auth.currentUser ?: return
        if (isLoading) return
        isLoading = true
        val generation = ++loadGeneration
        setLoading(true)
        clearError()

        val userRef = db.collection("users").document(user.uid)
        val totalTasks = collectionNames.size + 1 // profile plus each collection
        var completed = 0
        var fromCacheLoads = 0
        val failed = mutableListOf<String>()

        fun taskFinished() {
            if (generation != loadGeneration || completed != totalTasks) return
            isLoading = false
            setLoading(false)
            if (fromCacheLoads == 0) {
                lastUpdated = Date()
                cachePreferences.edit().putLong("last_updated", lastUpdated!!.time).apply()
            }
            renderInsights()
            tvInsightsLastUpdated.text = if (fromCacheLoads > 0) {
                val syncText = lastUpdated?.let { "Last cloud sync: ${formatDateTime(it)}" } ?: "Previous cloud sync time unavailable"
                "Showing cached data · $syncText"
            } else {
                "Last refreshed: ${formatDateTime(lastUpdated ?: Date())} · Saved on this device"
            }
            if (failed.isNotEmpty()) {
                showError("Some data could not be refreshed (${failed.joinToString()}). Showing any available saved records. Check your connection and Firestore rules.")
            }
        }

        userRef.get()
            .addOnSuccessListener { profile ->
                if (profile.metadata.isFromCache) fromCacheLoads++
                if (generation == loadGeneration) {
                    profileFields = profile.data ?: emptyMap()
                    saveProfileCache(profileFields)
                }
                completed++
                taskFinished()
            }
            .addOnFailureListener { exception ->
                failed.add("profile")
                android.util.Log.w("InsightsActivity", "Profile refresh failed", exception)
                completed++
                taskFinished()
            }

        collectionNames.forEach { collection ->
            userRef.collection(collection).get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.metadata.isFromCache) fromCacheLoads++
                    if (generation == loadGeneration) {
                        val docs = snapshot.documents.map { toCachedDocument(it) }
                        cachedCollections[collection] = docs
                        saveCollectionCache(collection, docs)
                    }
                    completed++
                    taskFinished()
                }
                .addOnFailureListener { exception ->
                    failed.add(collection)
                    android.util.Log.w("InsightsActivity", "Refresh failed for $collection", exception)
                    completed++
                    taskFinished()
                }
        }
    }

    // -------------------------------------------------------------------------
    // INSIGHTS CALCULATIONS
    // -------------------------------------------------------------------------

    private fun renderInsights() {
        updatePeriodDisplay()

        val salaries = monthlyDocuments("salaries", listOf("salaryDate", "paymentDate", "date", "createdAt"))
        val expenses = monthlyDocuments("expenses", listOf("date", "transactionDate", "createdAt"))
        val savings = monthlyDocuments("savings", listOf("date", "transactionDate", "createdAt"))
        val payments = monthlyDocuments("payments", listOf("date", "paymentDate", "transactionDate", "createdAt"))

        val income = salaries.sumOf { amountFrom(it.fields, "netSalary", "netAmount", "netPay", "takeHomePay", "amount") }
        val expenseTotal = expenses.sumOf { amountFrom(it.fields, "amount", "total") }
        val paymentTotal = payments.sumOf { amountFrom(it.fields, "amount", "paymentAmount", "paidAmount") }

        var contributions = 0.0
        var withdrawals = 0.0
        savings.forEach { movement ->
            val amount = amountFrom(movement.fields, "amount", "transactionAmount")
            val type = stringFrom(movement.fields, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            if (type.contains("withdraw")) withdrawals += amount else contributions += amount
        }

        val netCashFlow = income - expenseTotal - paymentTotal - contributions + withdrawals
        val netSavings = contributions - withdrawals
        val savingsRate = if (income > 0.0) (netSavings / income * 100.0) else 0.0

        tvInsightsIncome.text = formatCurrency(income)
        tvInsightsExpenses.text = formatCurrency(expenseTotal)
        tvInsightsNetCashFlow.text = "Net Cash Flow: ${formatCurrency(netCashFlow)}"
        tvInsightsNetCashFlow.setTextColor(if (netCashFlow >= 0.0) Color.WHITE else Color.rgb(255, 210, 210))

        updateFinancialSnapshot(income, expenseTotal, netCashFlow)
        updateSpendingAnalysis(expenses, expenseTotal)
        updateSpendingHabits(expenses, expenseTotal, savingsRate)
        updateBudgetPerformance(expenses)
        updatePaydayReadiness(income, expenseTotal, paymentTotal, contributions, withdrawals)
        updateSavingsOpportunities(expenseTotal, netSavings, savingsRate)
        updateDebtManagement()
        updateAlerts(income, expenseTotal, netCashFlow, savingsRate)
        updateRecommendations(income, expenseTotal, netCashFlow, savingsRate)
    }

    private fun monthlyDocuments(collection: String, dateFields: List<String>): List<CachedDocument> =
        cachedCollections[collection].orEmpty().filter { document ->
            val date = dateFrom(document.fields, dateFields)
            date != null && isInSelectedPeriod(date)
        }

    private fun updateFinancialSnapshot(income: Double, expenses: Double, net: Double) {
        val (title, description) = when {
            income == 0.0 && expenses == 0.0 -> "Let's build your financial picture" to
                    "No income or expense records were found for this month. Add your transactions to receive useful insights."
            net < 0.0 -> "Spending is ahead of income" to
                    "Your recorded outflows exceed income for this month. Review upcoming commitments and discretionary spending."
            income > 0.0 && expenses / income >= 0.8 -> "Your budget needs attention" to
                    "Most of your recorded income has already been allocated to expenses and payments. Review your largest spending categories."
            income > 0.0 && expenses / income <= 0.6 -> "You're keeping spending in check" to
                    "Your recorded expenses are below 60% of income for this month. Keep monitoring commitments and savings transfers."
            else -> "Your finances are moving steadily" to
                    "Review the alerts and suggestions below to identify practical ways to improve your monthly cash flow."
        }
        tvFinancialStatusTitle.text = title
        tvFinancialStatusDescription.text = description
    }

    private fun updateSpendingAnalysis(expenses: List<CachedDocument>, total: Double) {
        insightsCategoryContainer.removeAllViews()
        val grouped = expenses.groupBy {
            stringFrom(it.fields, "category").ifBlank { "Other" }
        }.mapValues { (_, docs) -> docs.sumOf { amountFrom(it.fields, "amount", "total") } }
            .toList().sortedByDescending { it.second }

        tvInsightsCategoryEmpty.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
        if (grouped.isEmpty()) return
        val max = grouped.maxOf { it.second }.coerceAtLeast(1.0)
        grouped.take(8).forEach { (category, amount) ->
            val share = if (total > 0.0) amount / total * 100.0 else 0.0
            insightsCategoryContainer.addView(createMetricBar(category, "${formatCurrency(amount)} · ${share.roundToInt()}%", amount, max, purple))
        }
    }

    private fun updateSpendingHabits(expenses: List<CachedDocument>, total: Double, savingsRate: Double) {
        val today = Calendar.getInstance(timezone)
        val period = selectedPeriod.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                selectedPeriod.get(Calendar.MONTH) == today.get(Calendar.MONTH)
        val days = if (period) today.get(Calendar.DAY_OF_MONTH) else selectedPeriod.getActualMaximum(Calendar.DAY_OF_MONTH)
        tvAverageDailySpending.text = formatCurrency(total / days.coerceAtLeast(1))

        val largest = expenses.maxByOrNull { amountFrom(it.fields, "amount", "total") }
        val largestAmount = largest?.let { amountFrom(it.fields, "amount", "total") } ?: 0.0
        tvLargestRecordedExpense.text = formatCurrency(largestAmount)
        tvLargestExpenseDescription.text = largest?.let {
            val description = stringFrom(it.fields, "description", "name").ifBlank { "Expense" }
            val category = stringFrom(it.fields, "category").ifBlank { "Other" }
            "$description · $category"
        } ?: "No recorded expenses for this period."

        tvInsightsSavingsRate.text = String.format(Locale.US, "%.1f%%", savingsRate)
        progressInsightsSavings.max = 100
        progressInsightsSavings.progress = savingsRate.roundToInt().coerceIn(0, 100)
    }

    // -------------------------------------------------------------------------
    // CATEGORY BUDGETS
    // -------------------------------------------------------------------------

    private fun updateBudgetPerformance(expenses: List<CachedDocument>) {
        insightsBudgetContainer.removeAllViews()
        val budgetMap = findBudgetMap()
        val budgets = budgetMap.mapNotNull { (category, value) ->
            val limit = when (value) {
                is Map<*, *> -> amountFromAnyMap(value, "amount", "limit", "monthlyLimit", "budget")
                else -> parseAmount(value)
            }
            if (category.isNotBlank() && limit > 0.0) category to limit else null
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        tvInsightsBudgetEmpty.visibility = if (budgets.isEmpty()) View.VISIBLE else View.GONE
        if (budgets.isEmpty()) return

        budgets.forEach { (category, limit) ->
            val spent = expenses.filter {
                stringFrom(it.fields, "category").equals(category, ignoreCase = true)
            }.sumOf { amountFrom(it.fields, "amount", "total") }
            val percent = if (limit > 0.0) spent / limit * 100.0 else 0.0
            val tint = when {
                percent >= 100.0 -> red
                percent >= 80.0 -> orange
                else -> green
            }
            insightsBudgetContainer.addView(
                createMetricBar(category, "${formatCurrency(spent)} / ${formatCurrency(limit)} · ${percent.roundToInt()}%", spent, limit, tint)
            )
        }
    }

    private fun findBudgetMap(): Map<String, Any?> {
        val candidateKeys = listOf("categoryBudgets", "monthlyBudgets", "budgetLimits", "budgets")
        candidateKeys.forEach { key ->
            val value = profileFields[key]
            if (value is Map<*, *>) {
                val result = mutableMapOf<String, Any?>()
                value.forEach { (category, amount) -> if (category is String) result[category] = amount }
                if (result.isNotEmpty()) return result
            }
        }
        return emptyMap()
    }

    // -------------------------------------------------------------------------
    // PAYDAY READINESS AND FORECAST
    // -------------------------------------------------------------------------

    private fun updatePaydayReadiness(
        selectedIncome: Double,
        selectedExpenses: Double,
        selectedPayments: Double,
        selectedContributions: Double,
        selectedWithdrawals: Double
    ) {
        val dayOne = intFrom(profileFields, "paydayDayOne").takeIf { it in 1..31 } ?: 15
        val rawDayTwo = intFrom(profileFields, "paydayDayTwo").takeIf { it in 0..31 } ?: 30
        val amountOne = amountFrom(profileFields, "paydayAmountOne", "expectedPaydayAmount", "expectedSalary", "salaryAmount")
        val amountTwo = amountFrom(profileFields, "paydayAmountTwo")
        val next = nextPayday(dayOne, rawDayTwo)
        tvInsightsNextPayday.text = if (next == null) "Not scheduled" else {
            val label = if (next.second == 1) "First payday" else "Second payday"
            "$label · ${formatDate(next.first)}"
        }

        val nextDate = next?.first ?: endOfDay(Date())
        val upcomingObligations = cachedCollections["obligations"].orEmpty().sumOf { doc ->
            val due = dateFrom(doc.fields, listOf("dueDate", "date", "paymentDate")) ?: return@sumOf 0.0
            val status = stringFrom(doc.fields, "status")
            if (isClosedStatus(status) || due.before(startOfDay(Date())) || due.after(endOfDay(nextDate))) 0.0
            else remainingObligation(doc.fields)
        }
        tvInsightsUpcomingObligations.text = formatCurrency(upcomingObligations)

        val allIncome = cachedCollections["salaries"].orEmpty().sumOf {
            amountFrom(it.fields, "netSalary", "netAmount", "netPay", "takeHomePay", "amount")
        }
        val allExpenses = cachedCollections["expenses"].orEmpty().sumOf {
            amountFrom(it.fields, "amount", "total")
        }
        val allPayments = cachedCollections["payments"].orEmpty().sumOf {
            amountFrom(it.fields, "amount", "paymentAmount", "paidAmount")
        }
        var allContributions = 0.0
        var allWithdrawals = 0.0
        cachedCollections["savings"].orEmpty().forEach { item ->
            val amount = amountFrom(item.fields, "amount", "transactionAmount")
            val type = stringFrom(item.fields, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            if (type.contains("withdraw")) allWithdrawals += amount else allContributions += amount
        }
        val opening = amountFrom(profileFields, "openingBalance", "initialBalance")
        val recordedCash = opening + allIncome - allExpenses - allPayments - allContributions + allWithdrawals
        val projected = recordedCash - upcomingObligations
        tvInsightsProjectedCash.text = formatCurrency(projected)
        tvInsightsProjectedCash.setTextColor(if (projected >= 0.0) green else red)

        val expected = if (next?.second == 1) amountOne else amountTwo
        val advice = when {
            next == null -> "Set your payday schedule in the Calendar screen to improve this forecast."
            projected < 0.0 -> "Your recorded cash may not cover all upcoming obligations before payday. Review due dates and avoid non-essential spending."
            upcomingObligations <= 0.0 -> "No unpaid obligations were found before your next payday. Keep your records up to date."
            else -> "After ${formatCurrency(upcomingObligations)} in upcoming obligations, your estimated cash is ${formatCurrency(projected)}. Expected payday amount: ${if (expected > 0.0) formatCurrency(expected) else "not set"}."
        }
        tvInsightsPaydayAdvice.text = advice
    }

    private fun nextPayday(dayOne: Int, dayTwo: Int): Pair<Date, Int>? {
        val today = startOfDay(Date())
        val base = Calendar.getInstance(timezone).apply {
            time = today
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val candidates = mutableListOf<Pair<Date, Int>>()
        for (offset in 0..3) {
            val month = (base.clone() as Calendar).apply { add(Calendar.MONTH, offset) }
            candidates.add(paydayDate(month.get(Calendar.YEAR), month.get(Calendar.MONTH), dayOne) to 1)
            candidates.add(paydayDate(month.get(Calendar.YEAR), month.get(Calendar.MONTH), dayTwo) to 2)
        }
        return candidates.filter { !it.first.before(today) }.minByOrNull { it.first }
    }

    private fun paydayDate(year: Int, month: Int, requestedDay: Int): Date {
        val cal = Calendar.getInstance(timezone)
        cal.clear()
        cal.set(year, month, 1, 12, 0, 0)
        val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val actual = if (requestedDay == 0) last else requestedDay.coerceIn(1, last)
        cal.set(Calendar.DAY_OF_MONTH, actual)
        return cal.time
    }

    // -------------------------------------------------------------------------
    // SAVINGS OPPORTUNITIES
    // -------------------------------------------------------------------------

    private fun updateSavingsOpportunities(expenses: Double, netSavings: Double, savingsRate: Double) {
        insightsSavingsContainer.removeAllViews()
        val suggestions = mutableListOf<String>()
        val monthExpenses = monthlyDocuments("expenses", listOf("date", "transactionDate", "createdAt"))
        val topCategory = monthExpenses.groupBy {
            stringFrom(it.fields, "category").ifBlank { "Other" }
        }.mapValues { (_, docs) -> docs.sumOf { amountFrom(it.fields, "amount", "total") } }
            .maxByOrNull { it.value }

        if (expenses <= 0.0) {
            suggestions.add("Record your day-to-day expenses to reveal the categories where you could save.")
        } else if (topCategory != null && topCategory.value / expenses >= 0.35) {
            suggestions.add("${topCategory.key} accounts for ${(topCategory.value / expenses * 100).roundToInt()}% of your spending. Set a realistic limit for this category and review it weekly.")
        }
        if (savingsRate < 10.0) {
            suggestions.add("Your recorded net savings rate is ${String.format(Locale.US, "%.1f", savingsRate)}%. Consider setting aside a small amount on each payday.")
        } else {
            suggestions.add("Your recorded savings movements are positive. Keep contributions consistent and avoid withdrawals for non-essential purchases.")
        }
        val activeGoals = cachedCollections["savingsGoals"].orEmpty().filter {
            !isClosedStatus(stringFrom(it.fields, "status"))
        }
        val unfinished = activeGoals.count { goal ->
            val target = amountFrom(goal.fields, "targetAmount", "target", "goalAmount")
            val saved = amountFrom(goal.fields, "currentSaved", "savedAmount", "amountSaved", "saved")
            target > saved
        }
        if (unfinished > 0) suggestions.add("You have $unfinished savings goal(s) still in progress. Schedule an automatic reminder to contribute after payday.")

        tvInsightsSavingsEmpty.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.GONE
        suggestions.take(4).forEachIndexed { index, text ->
            insightsSavingsContainer.addView(createMessageCard("Savings idea ${index + 1}", text, "good"))
        }
    }

    // -------------------------------------------------------------------------
    // DEBT MANAGEMENT
    // -------------------------------------------------------------------------

    private fun updateDebtManagement() {
        val loans = cachedCollections["loans"].orEmpty()
        var principalTotal = 0.0
        var repaidTotal = 0.0
        var outstandingTotal = 0.0
        loans.forEach { loan ->
            val status = stringFrom(loan.fields, "status")
            if (isClosedStatus(status)) return@forEach
            val principal = amountFrom(loan.fields, "principal", "loanPrincipal", "principalAmount", "amount")
            val repaid = amountFrom(loan.fields, "totalRepaid", "amountPaid", "paidAmount")
            val outstanding = explicitAmountOrNull(loan.fields, "outstandingBalance", "remainingBalance", "currentBalance")
                ?.coerceAtLeast(0.0) ?: (principal - repaid).coerceAtLeast(0.0)
            principalTotal += principal
            repaidTotal += repaid
            outstandingTotal += outstanding
        }
        val denominator = principalTotal.coerceAtLeast(repaidTotal + outstandingTotal)
        val progress = if (denominator > 0.0) (repaidTotal / denominator * 100.0).roundToInt().coerceIn(0, 100) else 0
        tvInsightsOutstandingDebt.text = formatCurrency(outstandingTotal)
        tvInsightsDebtRepaid.text = formatCurrency(repaidTotal)
        progressInsightsDebt.max = 100
        progressInsightsDebt.progress = progress
        tvInsightsDebtProgress.text = "$progress% of recorded debt repaid"
        tvInsightsDebtAdvice.text = when {
            loans.isEmpty() -> "No loan records were found. Add your active loans to monitor balances and repayment progress."
            outstandingTotal <= 0.0 -> "Your recorded loans have no outstanding balance. Keep payment records for future reference."
            repaidTotal <= 0.0 -> "Start by recording each payment and checking the interest rate and fees on your loan agreements."
            else -> "You have ${formatCurrency(outstandingTotal)} in recorded outstanding loan balances. Prioritize high-interest debt while keeping essential bills current."
        }
    }

    // -------------------------------------------------------------------------
    // ALERTS AND RECOMMENDATIONS
    // -------------------------------------------------------------------------

    private fun updateAlerts(income: Double, expenses: Double, net: Double, savingsRate: Double) {
        val alerts = mutableListOf<AlertItem>()
        if (income == 0.0 && expenses == 0.0) {
            alerts.add(AlertItem("No monthly activity", "No salary or expense records were found for ${tvInsightsPeriod.text}. Add records to generate more useful insights.", "info"))
        } else {
            if (net < 0.0) alerts.add(AlertItem("Negative cash flow", "Recorded outflows exceed income by ${formatCurrency(abs(net))} this month. Review spending and payments.", "danger"))
            if (income > 0.0 && expenses > income * 0.8) alerts.add(AlertItem("High spending level", "Expenses have reached ${(expenses / income * 100).roundToInt()}% of recorded income.", "warning"))
            if (income > 0.0 && savingsRate < 0.0) alerts.add(AlertItem("Savings withdrawals exceed contributions", "Your recorded net savings movement is negative for this month.", "warning"))
        }

        val top = monthlyDocuments("expenses", listOf("date", "transactionDate", "createdAt"))
            .groupBy { stringFrom(it.fields, "category").ifBlank { "Other" } }
            .mapValues { (_, docs) -> docs.sumOf { amountFrom(it.fields, "amount", "total") } }
            .maxByOrNull { it.value }
        if (top != null && expenses > 0.0 && top.value / expenses >= 0.5) {
            alerts.add(AlertItem("Spending is concentrated", "${top.key} represents ${(top.value / expenses * 100).roundToInt()}% of your recorded expenses.", "warning"))
        }

        val today = startOfDay(Date())
        val soon = (Calendar.getInstance(timezone).apply { time = today; add(Calendar.DAY_OF_MONTH, 7) }).time
        val dueSoon = cachedCollections["obligations"].orEmpty().filter { doc ->
            val due = dateFrom(doc.fields, listOf("dueDate", "date", "paymentDate")) ?: return@filter false
            !isClosedStatus(stringFrom(doc.fields, "status")) && !due.before(today) && !due.after(endOfDay(soon)) && remainingObligation(doc.fields) > 0.0
        }
        val dueTotal = dueSoon.sumOf { remainingObligation(it.fields) }
        if (dueSoon.isNotEmpty()) alerts.add(AlertItem("Bills due in the next 7 days", "${dueSoon.size} unpaid obligation(s) total ${formatCurrency(dueTotal)}.", "warning"))

        val overdueGoals = cachedCollections["savingsGoals"].orEmpty().count { goal ->
            val target = dateFrom(goal.fields, listOf("targetDate", "dueDate"))
            target != null && target.before(today) && !isClosedStatus(stringFrom(goal.fields, "status"))
        }
        if (overdueGoals > 0) alerts.add(AlertItem("Savings goal date passed", "$overdueGoals active savings goal(s) have a target date in the past. Review or update their target dates.", "info"))

        insightsAlertsList.removeAllViews()
        tvInsightsAlertsEmpty.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
        tvInsightsAlertCount.text = "${alerts.size} alert${if (alerts.size == 1) "" else "s"}"
        alerts.forEach { insightsAlertsList.addView(createMessageCard(it.title, it.message, it.tone)) }
    }

    private fun updateRecommendations(income: Double, expenses: Double, net: Double, savingsRate: Double) {
        val recommendations = mutableListOf<String>()
        if (net < 0.0) recommendations.add("Before your next payday, pause optional purchases and compare your upcoming bills against your expected cash.")
        if (income > 0.0 && expenses / income > 0.7) recommendations.add("Try a weekly spending cap. A weekly review can reveal small purchases that add up across the month.")
        if (savingsRate < 10.0) recommendations.add("Choose a manageable savings amount and transfer it after payday rather than waiting until month-end.")
        if (cachedCollections["obligations"].orEmpty().any { !isClosedStatus(stringFrom(it.fields, "status")) }) {
            recommendations.add("Keep due dates and paid status updated so the payday forecast reflects your remaining commitments.")
        }
        if (cachedCollections["loans"].orEmpty().any { !isClosedStatus(stringFrom(it.fields, "status")) }) {
            recommendations.add("Review loan interest and fees periodically. Direct extra payments toward the highest-interest balance when affordable.")
        }
        if (recommendations.isEmpty()) recommendations.add("Keep recording income, expenses and savings consistently. More complete records make these insights more meaningful.")

        insightsRecommendationContainer.removeAllViews()
        tvInsightsRecommendationsEmpty.visibility = View.GONE
        tvInsightsRecommendationCount.text = "${recommendations.size} suggestion${if (recommendations.size == 1) "" else "s"}"
        recommendations.take(6).forEachIndexed { index, recommendation ->
            insightsRecommendationContainer.addView(createMessageCard("Recommendation ${index + 1}", recommendation, "info"))
        }
    }

    // -------------------------------------------------------------------------
    // DYNAMIC UI HELPERS
    // -------------------------------------------------------------------------

    private fun createMetricBar(label: String, detail: String, value: Double, maximum: Double, color: Int): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply {
            text = label
            setTextColor(darkText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val amount = TextView(this).apply {
            text = detail
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        row.addView(name)
        row.addView(amount)
        wrapper.addView(row)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = ((value / maximum.coerceAtLeast(1.0)) * 1000).roundToInt().coerceIn(0, 1000)
            progressTintList = android.content.res.ColorStateList.valueOf(color)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(238, 239, 245))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(7) }
        }
        wrapper.addView(bar)
        return wrapper
    }

    private fun createMessageCard(title: String, message: String, tone: String): View {
        val (accent, bgTint) = when (tone) {
            "danger" -> red to redLight
            "warning" -> orange to orangeLight
            "good" -> green to greenLight
            else -> purple to purpleLight
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            this.background = roundedBackground(Color.WHITE, border, 11)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            val headingRow = LinearLayout(this@InsightsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val marker = TextView(this@InsightsActivity).apply {
                text = "•"
                gravity = Gravity.CENTER
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                this.background = roundedBackground(bgTint, bgTint, 8)
                layoutParams = LinearLayout.LayoutParams(dp(25), dp(25))
            }
            val heading = TextView(this@InsightsActivity).apply {
                text = title
                setTextColor(darkText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(9) }
            }
            headingRow.addView(marker)
            headingRow.addView(heading)
            addView(headingRow)
            addView(TextView(this@InsightsActivity).apply {
                text = message
                setTextColor(mutedText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(34), dp(5), 0, 0)
            })
        }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    // -------------------------------------------------------------------------
    // FIRESTORE / CACHE FIELD HELPERS
    // -------------------------------------------------------------------------

    private fun amountFrom(fields: Map<String, Any?>, vararg names: String): Double {
        for (name in names) if (fields.containsKey(name)) return parseAmount(fields[name])
        return 0.0
    }

    private fun explicitAmountOrNull(fields: Map<String, Any?>, vararg names: String): Double? {
        for (name in names) if (fields.containsKey(name)) return parseAmount(fields[name])
        return null
    }

    private fun amountFromAnyMap(fields: Map<*, *>, vararg names: String): Double {
        for (name in names) if (fields.containsKey(name)) return parseAmount(fields[name])
        return 0.0
    }

    private fun intFrom(fields: Map<String, Any?>, name: String): Int = when (val value = fields[name]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun stringFrom(fields: Map<String, Any?>, vararg names: String): String {
        for (name in names) {
            val value = fields[name]
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun parseAmount(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> {
            val text = value.trim()
            val negative = text.startsWith("(") && text.endsWith(")")
            val parsed = text.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0
            if (negative && parsed > 0.0) -parsed else parsed
        }
        else -> 0.0
    }

    private fun dateFrom(fields: Map<String, Any?>, names: List<String>): Date? {
        for (name in names) {
            when (val value = fields[name]) {
                is Timestamp -> return value.toDate()
                is Date -> return value
                is Number -> {
                    val raw = value.toLong()
                    return Date(if (raw < 100_000_000_000L) raw * 1000L else raw)
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
                return SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = timezone
                }.parse(value)
            } catch (_: Exception) {
                // Continue with the next accepted date pattern.
            }
        }
        return null
    }

    private fun remainingObligation(fields: Map<String, Any?>): Double {
        explicitAmountOrNull(fields, "remainingBalance", "outstandingAmount", "unpaidAmount")?.let {
            return it.coerceAtLeast(0.0)
        }
        val total = amountFrom(fields, "amount", "totalAmount")
        val paid = amountFrom(fields, "paidAmount", "amountPaid")
        return (total - paid).coerceAtLeast(0.0)
    }

    private fun isClosedStatus(status: String): Boolean = status.lowercase(Locale.ROOT) in setOf(
        "paid", "completed", "fully paid", "closed", "cancelled", "canceled", "rejected"
    )

    // -------------------------------------------------------------------------
    // DATE / FORMAT HELPERS
    // -------------------------------------------------------------------------

    private fun startOfDay(date: Date): Date = Calendar.getInstance(timezone).apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun endOfDay(date: Date): Date = Calendar.getInstance(timezone).apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time

    private fun formatCurrency(amount: Double): String = currencyFormatter.format(amount)

    private fun formatDate(date: Date): String = SimpleDateFormat("MMM d, yyyy", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun formatDateTime(date: Date): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).apply {
        timeZone = timezone
    }.format(date)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // -------------------------------------------------------------------------
    // LOADING AND ERROR STATES
    // -------------------------------------------------------------------------

    private fun setLoading(loading: Boolean) {
        insightsLoading.visibility = if (loading && !hasAnyCachedData()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnRefreshInsights).isEnabled = !loading
        findViewById<View>(R.id.btnInsightsChangePeriod).isEnabled = !loading
    }

    private fun showError(message: String) {
        tvInsightsError.text = message
        tvInsightsError.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvInsightsError.text = ""
        tvInsightsError.visibility = View.GONE
    }
}

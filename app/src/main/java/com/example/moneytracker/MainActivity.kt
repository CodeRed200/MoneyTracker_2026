package com.example.moneytracker
import android.util.Log
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    // Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Dashboard views
    private lateinit var tvGreeting: TextView
    private lateinit var tvAvailableBalance: TextView
    private lateinit var tvIncomeAmount: TextView
    private lateinit var tvExpenseAmount: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvTotalExpenses: TextView
    private lateinit var tvTotalSavings: TextView
    private lateinit var tvOutstandingDebt: TextView
    private lateinit var tvNextPayday: TextView
    private lateinit var tvPaydayDate: TextView
    private lateinit var tvPaydayAmount: TextView
    private lateinit var tvCurrentMonth: TextView
    private lateinit var tvUserAvatar: TextView
    private lateinit var transactionContainer: LinearLayout

    // Quick expense views
    private lateinit var etQuickExpenseDescription: EditText
    private lateinit var etQuickExpenseAmount: EditText
    private lateinit var btnSaveQuickExpense: Button

    private var selectedQuickCategory = "Other"
    private var isSavingQuickExpense = false

    private val manilaTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private val collectionNames = listOf(
        "salaries",
        "expenses",
        "savings",
        "payments",
        "loans",
        "obligations"
    )

    private data class DashboardTransaction(
        val title: String,
        val category: String,
        val amount: Double,
        val date: Date,
        val direction: Int,
        val source: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        setupNavigation()
        setupQuickExpense()
        updateCurrentMonthLabel()
    }

    override fun onResume() {
        super.onResume()
        checkUserSession()
    }

    private fun initializeViews() {
        tvGreeting = findViewById(R.id.tvGreeting)
        tvAvailableBalance = findViewById(R.id.tvAvailableBalance)
        tvIncomeAmount = findViewById(R.id.tvIncomeAmount)
        tvExpenseAmount = findViewById(R.id.tvExpenseAmount)
        tvTotalIncome = findViewById(R.id.tvTotalIncome)
        tvTotalExpenses = findViewById(R.id.tvTotalExpenses)
        tvTotalSavings = findViewById(R.id.tvTotalSavings)
        tvOutstandingDebt = findViewById(R.id.tvOutstandingDebt)
        tvNextPayday = findViewById(R.id.tvNextPayday)
        tvPaydayDate = findViewById(R.id.tvPaydayDate)
        tvPaydayAmount = findViewById(R.id.tvPaydayAmount)
        tvCurrentMonth = findViewById(R.id.tvCurrentMonth)
        tvUserAvatar = findViewById(R.id.tvUserAvatar)
        transactionContainer = findViewById(R.id.transactionContainer)

        etQuickExpenseDescription = findViewById(R.id.etQuickExpenseDescription)
        etQuickExpenseAmount = findViewById(R.id.etQuickExpenseAmount)
        btnSaveQuickExpense = findViewById(R.id.btnSaveQuickExpense)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.main)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
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

    // Session and authentication
    private fun checkUserSession() {
        val user = auth.currentUser

        if (user == null) {
            openLogin()
            return
        }

        if (!user.isEmailVerified) {
            openEmailVerification()
            return
        }

        loadDashboardData()
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

    // Bottom navigation
    private fun setupNavigation() {
        findViewById<View>(R.id.navDashboard).setOnClickListener {
            findViewById<ScrollView>(R.id.dashboardScroll).smoothScrollTo(0, 0)
        }

        findViewById<View>(R.id.navCalendar).setOnClickListener {
            openScreen(CalendarActivity::class.java)
        }

        findViewById<View>(R.id.navAdd).setOnClickListener {
            showAddOptions()
        }

        findViewById<View>(R.id.navActivity).setOnClickListener {
            openScreen(TransactionsActivity::class.java)
        }

        findViewById<View>(R.id.navMore).setOnClickListener {
            showMoreOptions()
        }

        findViewById<View>(R.id.btnSeeAllTransactions).setOnClickListener {
            openScreen(TransactionsActivity::class.java)
        }

        findViewById<View>(R.id.tvUserAvatar).setOnClickListener {
            openScreen(SettingsActivity::class.java)
        }

        findViewById<View>(R.id.btnNotifications).setOnClickListener {
            Toast.makeText(this, "Notifications are not configured yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddOptions() {
        val options = arrayOf(
            "Add Expense",
            "Add Salary",
            "Add Savings",
            "Record Bill Payment",
            "Record Loan Payment",
            "Create Savings Goal"
        )

        AlertDialog.Builder(this)
            .setTitle("Add a New Record")
            .setItems(options) { _, position ->
                when (position) {
                    0 -> openScreen(ExpensesActivity::class.java, true)
                    1 -> openScreen(SalaryActivity::class.java, true)
                    2 -> openScreen(SavingsActivity::class.java, true)
                    3 -> openScreen(ObligationsActivity::class.java, true)
                    4 -> openScreen(LoansActivity::class.java, true)
                    5 -> openScreen(GoalsActivity::class.java, true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoreOptions() {
        val options = arrayOf(
            "Smart Financial Insights",
            "Analytics",
            "Salary & Benefits",
            "Expenses",
            "Savings",
            "Loans & Interest",
            "Bills & Payments",
            "Savings Goals",
            "Reports",
            "Settings"
        )

        AlertDialog.Builder(this)
            .setTitle("More Features")
            .setItems(options) { _, position ->
                when (position) {
                    0 -> openScreen(InsightsActivity::class.java)
                    1 -> openScreen(AnalyticsActivity::class.java)
                    2 -> openScreen(SalaryActivity::class.java)
                    3 -> openScreen(ExpensesActivity::class.java)
                    4 -> openScreen(SavingsActivity::class.java)
                    5 -> openScreen(LoansActivity::class.java)
                    6 -> openScreen(ObligationsActivity::class.java)
                    7 -> openScreen(GoalsActivity::class.java)
                    8 -> openScreen(ReportsActivity::class.java)
                    9 -> openScreen(SettingsActivity::class.java)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openScreen(activityClass: Class<*>, openForm: Boolean = false) {
        val intent = Intent(this, activityClass)
        if (openForm) intent.putExtra("open_form", true)
        startActivity(intent)
    }

    // Quick expense form
    private fun setupQuickExpense() {
        btnSaveQuickExpense.setOnClickListener { saveQuickExpense() }

        val categoryViews = mapOf(
            R.id.chipFood to "Food & Dining",
            R.id.chipTransport to "Transportation",
            R.id.chipGroceries to "Groceries",
            R.id.chipLoad to "Internet & Mobile",
            R.id.chipShopping to "Shopping"
        )

        categoryViews.forEach { (viewId, category) ->
            findViewById<View>(viewId).setOnClickListener {
                selectedQuickCategory = category
                updateCategoryChipStyles(categoryViews)
            }
        }

        updateCategoryChipStyles(categoryViews)
    }

    private fun updateCategoryChipStyles(categoryViews: Map<Int, String>) {
        categoryViews.forEach { (viewId, category) ->
            val chip = findViewById<TextView>(viewId)
            val selected = category == selectedQuickCategory

            chip.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (selected) Color.rgb(101, 88, 211) else Color.rgb(247, 247, 251))
                setStroke(
                    dp(1),
                    if (selected) Color.rgb(101, 88, 211) else Color.rgb(232, 234, 241)
                )
            }
            chip.setTextColor(if (selected) Color.WHITE else Color.rgb(80, 85, 107))
            chip.isSelected = selected
        }
    }

    private fun saveQuickExpense() {
        val user = auth.currentUser
        if (user == null) {
            openLogin()
            return
        }

        if (isSavingQuickExpense) return

        val description = etQuickExpenseDescription.text.toString().trim()
        val amountText = etQuickExpenseAmount.text.toString().trim()
        val amount = parseAmount(amountText)

        if (description.isBlank()) {
            etQuickExpenseDescription.error = "Enter an expense description"
            etQuickExpenseDescription.requestFocus()
            return
        }

        if (amount == null || amount <= 0.0) {
            etQuickExpenseAmount.error = "Enter a valid amount"
            etQuickExpenseAmount.requestFocus()
            return
        }

        if (amount > 999999999.99) {
            etQuickExpenseAmount.error = "Amount is too large"
            etQuickExpenseAmount.requestFocus()
            return
        }

        isSavingQuickExpense = true
        btnSaveQuickExpense.isEnabled = false
        btnSaveQuickExpense.text = "Saving..."

        val now = Date()
        val dateString = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = manilaTimeZone }
            .format(now)

        val calendar = Calendar.getInstance(manilaTimeZone).apply { time = now }
        val paydayPeriod = if (calendar.get(Calendar.DAY_OF_MONTH) >= 15) {
            "15th_salary"
        } else {
            "month_end_salary"
        }
        val paydayLabel = if (paydayPeriod == "15th_salary") {
            "15th salary period"
        } else {
            "Month-end salary period"
        }

        val expense = hashMapOf<String, Any>(
            "description" to description,
            "amount" to amount,
            "category" to selectedQuickCategory,
            "type" to "expense",
            "date" to Timestamp(now),
            "dateString" to dateString,
            "paydayPeriod" to paydayPeriod,
            "paydayPeriodLabel" to paydayLabel,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.collection("users")
            .document(user.uid)
            .collection("expenses")
            .add(expense)
            .addOnSuccessListener {
                finishQuickExpenseSave()
                etQuickExpenseDescription.text.clear()
                etQuickExpenseAmount.text.clear()
                selectedQuickCategory = "Other"

                val categoryViews = mapOf(
                    R.id.chipFood to "Food & Dining",
                    R.id.chipTransport to "Transportation",
                    R.id.chipGroceries to "Groceries",
                    R.id.chipLoad to "Internet & Mobile",
                    R.id.chipShopping to "Shopping"
                )
                updateCategoryChipStyles(categoryViews)

                Toast.makeText(this, "Expense saved successfully.", Toast.LENGTH_SHORT).show()
                loadDashboardData()
            }
            .addOnFailureListener { exception ->
                finishQuickExpenseSave()
                Toast.makeText(
                    this,
                    "Unable to save expense: ${exception.localizedMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun finishQuickExpenseSave() {
        isSavingQuickExpense = false
        btnSaveQuickExpense.isEnabled = true
        btnSaveQuickExpense.text = "＋ Save Expense"
    }

    // Dashboard data loading

    private fun loadDashboardData() {

        val user = auth.currentUser ?: return

        val userRef = db.collection("users")
            .document(user.uid)

        // Load user profile
        userRef.get()
            .addOnSuccessListener { profile ->

                val fullName = profile.getString("fullName")
                    ?: user.displayName
                    ?: "there"

                updateGreeting(fullName)
                updatePaydayInformation(profile)
            }
            .addOnFailureListener { exception ->

                Log.e(
                    "MainActivity",
                    "Failed to load user profile",
                    exception
                )
            }

        val snapshots = mutableMapOf<String, QuerySnapshot>()
        val failedCollections = mutableListOf<String>()

        var completedRequests = 0
        val totalRequests = collectionNames.size

        for (collectionName in collectionNames) {

            userRef.collection(collectionName)
                .get()
                .addOnSuccessListener { result ->

                    snapshots[collectionName] = result
                    completedRequests++

                    Log.d(
                        "MainActivity",
                        "$collectionName loaded: ${result.size()} records"
                    )

                    if (completedRequests == totalRequests) {

                        updateDashboard(
                            snapshots,
                            failedCollections
                        )
                    }
                }
                .addOnFailureListener { exception ->

                    failedCollections.add(collectionName)

                    Log.e(
                        "MainActivity",
                        "Failed to load collection: $collectionName",
                        exception
                    )

                    completedRequests++

                    if (completedRequests == totalRequests) {

                        updateDashboard(
                            snapshots,
                            failedCollections
                        )
                    }
                }
        }
    }

    private fun updateCurrentMonthLabel() {
        tvCurrentMonth.text = SimpleDateFormat("MMMM yyyy", Locale.US)
            .apply { timeZone = manilaTimeZone }
            .format(Date())
    }

    private fun updateGreeting(name: String) {
        val hour = Calendar.getInstance(manilaTimeZone).get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        tvGreeting.text = "$greeting, $name 👋"
    }

    private fun updateAvatar(name: String) {
        val initials = name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
        tvUserAvatar.text = initials.ifBlank { "U" }
    }

    // Uses the 15th and the last calendar day as the user's regular payday dates.
    // The card always shows the next date after today, not today's payday.
    private fun updatePaydayInformation(profile: DocumentSnapshot) {
        val calendar = Calendar.getInstance(manilaTimeZone)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        val paydayLabel: String
        when {
            currentDay < 15 -> {
                calendar.set(Calendar.DAY_OF_MONTH, 15)
                paydayLabel = "15th Salary"
            }
            currentDay < lastDay -> {
                calendar.set(Calendar.DAY_OF_MONTH, lastDay)
                paydayLabel = "Month-end Salary"
            }
            else -> {
                calendar.add(Calendar.MONTH, 1)
                calendar.set(Calendar.DAY_OF_MONTH, 15)
                paydayLabel = "15th Salary"
            }
        }

        tvNextPayday.text = paydayLabel
        tvPaydayDate.text = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
            .apply { timeZone = manilaTimeZone }
            .format(calendar.time)

        val expectedAmount = listOf(
            "expectedPaydayAmount",
            "expectedSalary",
            "salaryAmount"
        ).map { field -> amountFrom(profile, field) }
            .firstOrNull { it > 0.0 } ?: 0.0

        tvPaydayAmount.text = if (expectedAmount > 0.0) {
            formatCurrency(expectedAmount)
        } else {
            "Not set"
        }
    }

    // Financial calculations
    private fun updateDashboard(
        snapshots: Map<String, QuerySnapshot>,
        failedCollections: List<String>
    ) {
        val salaryDocs = snapshots["salaries"]?.documents.orEmpty()
        val expenseDocs = snapshots["expenses"]?.documents.orEmpty()
        val savingsDocs = snapshots["savings"]?.documents.orEmpty()
        val paymentDocs = snapshots["payments"]?.documents.orEmpty()
        val loanDocs = snapshots["loans"]?.documents.orEmpty()
        val obligationDocs = snapshots["obligations"]?.documents.orEmpty()
        val now = Date()

        var totalIncome = 0.0
        var monthlyIncome = 0.0
        salaryDocs.forEach { doc ->
            val amount = amountFrom(
                doc,
                "netSalary",
                "netAmount",
                "netPay",
                "takeHomePay",
                "amount"
            )
            totalIncome += amount
            val date = dateFrom(doc)
            if (date != null && isSameMonth(date, now)) monthlyIncome += amount
        }

        var totalExpenses = 0.0
        var monthlyExpenses = 0.0
        expenseDocs.forEach { doc ->
            val amount = amountFrom(doc, "amount", "total")
            totalExpenses += amount
            val date = dateFrom(doc)
            if (date != null && isSameMonth(date, now)) monthlyExpenses += amount
        }

        var totalContributions = 0.0
        var totalWithdrawals = 0.0
        savingsDocs.forEach { doc ->
            val amount = amountFrom(doc, "amount", "transactionAmount")
            val type = stringFrom(doc, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            if (type.contains("withdraw")) {
                totalWithdrawals += amount
            } else if (
                type.contains("contribution") ||
                type.contains("deposit") ||
                type.isBlank()
            ) {
                totalContributions += amount
            }
        }
        val totalSavings = (totalContributions - totalWithdrawals).coerceAtLeast(0.0)

        var totalPayments = 0.0
        paymentDocs.forEach { doc ->
            totalPayments += amountFrom(doc, "amount", "paymentAmount", "paidAmount")
        }

        // This is the cash-flow calculation for this data model:
        // salary - expenses - savings transfers - separately recorded payments.
        // Avoid recording the same bill/loan payment as both an expense and a payment,
        // otherwise that outflow will be deducted twice.
        val availableCash = totalIncome - totalExpenses - totalContributions + totalWithdrawals - totalPayments

        var outstandingLoans = 0.0
        loanDocs.forEach { doc ->
            val status = stringFrom(doc, "status").lowercase(Locale.ROOT)
            if (status in listOf("paid", "closed", "cancelled", "canceled")) return@forEach

            var outstanding = amountFrom(
                doc,
                "outstandingBalance",
                "remainingBalance",
                "currentBalance"
            )
            if (outstanding <= 0.0) {
                val principal = amountFrom(doc, "principal", "loanPrincipal", "principalAmount")
                val repaid = amountFrom(doc, "totalRepaid", "amountPaid", "paidAmount")
                outstanding = (principal - repaid).coerceAtLeast(0.0)
            }
            outstandingLoans += outstanding
        }

        var outstandingBills = 0.0
        obligationDocs.forEach { doc ->
            val status = stringFrom(doc, "status").lowercase(Locale.ROOT)
            val type = stringFrom(doc, "type", "obligationType").lowercase(Locale.ROOT)

            // Loan obligations are already included in the loans collection.
            if (type.contains("loan")) return@forEach
            if (status in listOf("paid", "completed", "cancelled", "canceled", "rejected")) return@forEach

            var remaining = amountFrom(
                doc,
                "remainingBalance",
                "outstandingAmount",
                "unpaidAmount"
            )
            if (remaining <= 0.0) {
                val originalAmount = amountFrom(doc, "amount", "totalAmount")
                val paidAmount = amountFrom(doc, "paidAmount", "amountPaid")
                remaining = (originalAmount - paidAmount).coerceAtLeast(0.0)
            }
            outstandingBills += remaining
        }

        val totalOutstandingDebt = outstandingLoans + outstandingBills

        tvAvailableBalance.text = formatCurrency(availableCash)
        tvIncomeAmount.text = formatCurrency(monthlyIncome)
        tvExpenseAmount.text = formatCurrency(monthlyExpenses)
        tvTotalIncome.text = formatCurrency(totalIncome)
        tvTotalExpenses.text = formatCurrency(totalExpenses)
        tvTotalSavings.text = formatCurrency(totalSavings)
        tvOutstandingDebt.text = formatCurrency(totalOutstandingDebt)

        val transactions = buildDashboardTransactions(
            salaryDocs,
            expenseDocs,
            savingsDocs,
            paymentDocs
        )
        displayRecentTransactions(transactions)

        if (failedCollections.isNotEmpty()) {

            Toast.makeText(
                this,
                "Failed collections: ${failedCollections.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildDashboardTransactions(
        salaryDocs: List<DocumentSnapshot>,
        expenseDocs: List<DocumentSnapshot>,
        savingsDocs: List<DocumentSnapshot>,
        paymentDocs: List<DocumentSnapshot>
    ): List<DashboardTransaction> {
        val result = mutableListOf<DashboardTransaction>()

        salaryDocs.forEach { doc ->
            result.add(
                DashboardTransaction(
                    title = stringFrom(doc, "description", "salaryName", "source").ifBlank { "Salary received" },
                    category = "Salary",
                    amount = amountFrom(doc, "netSalary", "netAmount", "netPay", "takeHomePay", "amount"),
                    date = dateFrom(doc) ?: Date(0L),
                    direction = 1,
                    source = "salaries"
                )
            )
        }

        expenseDocs.forEach { doc ->
            result.add(
                DashboardTransaction(
                    title = stringFrom(doc, "description", "name").ifBlank { "Expense" },
                    category = stringFrom(doc, "category").ifBlank { "Expense" },
                    amount = amountFrom(doc, "amount", "total"),
                    date = dateFrom(doc) ?: Date(0L),
                    direction = -1,
                    source = "expenses"
                )
            )
        }

        savingsDocs.forEach { doc ->
            val type = stringFrom(doc, "transactionType", "type", "movementType").lowercase(Locale.ROOT)
            val isWithdrawal = type.contains("withdraw")
            result.add(
                DashboardTransaction(
                    title = if (isWithdrawal) "Savings withdrawal" else "Savings contribution",
                    category = stringFrom(doc, "accountName", "account", "category").ifBlank { "Savings" },
                    amount = amountFrom(doc, "amount", "transactionAmount"),
                    date = dateFrom(doc) ?: Date(0L),
                    direction = if (isWithdrawal) 1 else -1,
                    source = "savings"
                )
            )
        }

        paymentDocs.forEach { doc ->
            result.add(
                DashboardTransaction(
                    title = stringFrom(doc, "description", "payee", "obligationName", "loanName").ifBlank { "Payment" },
                    category = stringFrom(doc, "type", "paymentType", "category").ifBlank { "Payment" },
                    amount = amountFrom(doc, "amount", "paymentAmount", "paidAmount"),
                    date = dateFrom(doc) ?: Date(0L),
                    direction = -1,
                    source = "payments"
                )
            )
        }

        return result.sortedByDescending { it.date }
    }

    private fun displayRecentTransactions(transactions: List<DashboardTransaction>) {
        transactionContainer.removeAllViews()

        if (transactions.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No transactions recorded yet.\nYour financial activity will appear here."
                setTextColor(Color.rgb(133, 139, 158))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(25), dp(16), dp(25))
            }
            transactionContainer.addView(emptyText)
            return
        }

        transactions.take(6).forEach { transaction ->
            transactionContainer.addView(createTransactionRow(transaction))
        }
    }

    private fun createTransactionRow(transaction: DashboardTransaction): View {
        val isIncoming = transaction.direction > 0
        val accentColor = if (isIncoming) Color.rgb(20, 155, 113) else Color.rgb(216, 93, 104)
        val iconBackground = if (isIncoming) Color.rgb(231, 247, 240) else Color.rgb(255, 241, 241)

        val symbol = when (transaction.source) {
            "salaries" -> "₱"
            "expenses" -> "−"
            "savings" -> if (isIncoming) "↓" else "↑"
            else -> "₱"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = roundedBackground(Color.WHITE, Color.rgb(235, 237, 243), 14)
        }

        val icon = TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(iconBackground, iconBackground, 12)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(11) }
        }

        val title = TextView(this).apply {
            text = transaction.title
            setTextColor(Color.rgb(32, 35, 52))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        val dateText = if (transaction.date.time == 0L) {
            "Date not available"
        } else {
            SimpleDateFormat("MMM d, yyyy", Locale.US)
                .apply { timeZone = manilaTimeZone }
                .format(transaction.date)
        }

        val subtitle = TextView(this).apply {
            text = "${transaction.category} · $dateText"
            setTextColor(Color.rgb(133, 139, 158))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        }

        details.addView(title)
        details.addView(subtitle)

        val amount = TextView(this).apply {
            val sign = if (isIncoming) "+" else "−"
            text = sign + formatCurrency(kotlin.math.abs(transaction.amount))
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            maxLines = 1
        }

        row.addView(icon)
        row.addView(details)
        row.addView(amount)
        return row
    }

    // Firestore value helpers
    private fun amountFrom(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            val value = document.get(field) ?: continue
            val parsed = parseAmount(value)
            if (parsed != null) return parsed
        }
        return 0.0
    }

    private fun parseAmount(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isBlank()) return null
                val isAccountingNegative = trimmed.startsWith("(") && trimmed.endsWith(")")
                val cleaned = trimmed.replace(Regex("[^0-9.\\-]"), "")
                val parsed = cleaned.toDoubleOrNull() ?: return null
                if (isAccountingNegative && parsed > 0.0) -parsed else parsed
            }
            else -> null
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
        val dateFields = listOf(
            "date",
            "transactionDate",
            "paymentDate",
            "salaryDate",
            "dateString",
            "createdAt",
            "timestamp"
        )

        for (field in dateFields) {
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

        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = manilaTimeZone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) {
                // Try the next supported date format.
            }
        }
        return null
    }

    private fun isSameMonth(firstDate: Date, secondDate: Date): Boolean {
        val first = Calendar.getInstance(manilaTimeZone).apply { time = firstDate }
        val second = Calendar.getInstance(manilaTimeZone).apply { time = secondDate }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
                first.get(Calendar.MONTH) == second.get(Calendar.MONTH)
    }

    private fun formatCurrency(amount: Double): String {
        return NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(amount)
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        cornerRadius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setCornerRadius(dp(cornerRadius).toFloat())
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

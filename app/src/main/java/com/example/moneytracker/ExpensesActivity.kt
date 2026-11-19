package com.example.moneytracker

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max

class ExpensesActivity : AppCompatActivity() {

    // =====================================================
    // FIREBASE AND LOCAL STORAGE
    // =====================================================

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userId: String = ""
    private var expenseListener: ListenerRegistration? = null
    private lateinit var preferences: android.content.SharedPreferences
    private var hasReceivedCloudSnapshot = false

    private val timezone = TimeZone.getTimeZone("Asia/Manila")
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    // =====================================================
    // UI
    // =====================================================

    private lateinit var tvExpenseMonth: TextView
    private lateinit var tvTotalExpenseAmount: TextView
    private lateinit var tvExpenseMonthlyComparison: TextView
    private lateinit var tvExpenseCount: TextView
    private lateinit var tvAverageExpense: TextView
    private lateinit var tvLargestExpense: TextView
    private lateinit var tvExpenseFilterSummary: TextView
    private lateinit var tvExpenseRecordCount: TextView
    private lateinit var tvExpenseCategoryEmpty: TextView
    private lateinit var expenseCategoryBreakdown: LinearLayout
    private lateinit var expenseListContainer: LinearLayout
    private lateinit var expenseEmptyState: View
    private lateinit var expenseLoading: View
    private lateinit var tvExpenseError: TextView
    private lateinit var etExpenseSearch: EditText
    private lateinit var spExpenseCategory: Spinner
    private lateinit var spExpensePaydayPeriod: Spinner

    // =====================================================
    // STATE AND DATA MODEL
    // =====================================================

    private data class ExpenseRecord(
        val id: String,
        val description: String,
        val amount: Double,
        val category: String,
        val dateMillis: Long,
        val paydayPeriod: String,
        val paymentMethod: String,
        val reference: String,
        val notes: String,
        val createdAtMillis: Long
    ) {
        val date: Date get() = Date(dateMillis)
        val paydayLabel: String
            get() = if (paydayPeriod == PERIOD_15TH) "15th salary period" else "Month-end salary period"
    }

    private val expenses = mutableListOf<ExpenseRecord>()
    private val monthCalendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"))
    private var selectedQuickCategory = "Food & Dining"
    private var isNavigatingAway = false

    private val categories = listOf(
        "Food & Dining",
        "Transportation",
        "Groceries",
        "Internet & Mobile",
        "Shopping",
        "Bills & Utilities",
        "Health",
        "Education",
        "Entertainment",
        "Personal Care",
        "Other"
    )

    private val quickCategoryViews = linkedMapOf(
        R.id.chipExpenseFood to "Food & Dining",
        R.id.chipExpenseTransport to "Transportation",
        R.id.chipExpenseGroceries to "Groceries",
        R.id.chipExpenseLoad to "Internet & Mobile",
        R.id.chipExpenseShopping to "Shopping"
    )

    private val purple = Color.rgb(101, 88, 211)
    private val green = Color.rgb(20, 155, 113)
    private val red = Color.rgb(216, 93, 104)
    private val orange = Color.rgb(213, 138, 54)
    private val darkText = Color.rgb(32, 35, 52)
    private val mutedText = Color.rgb(133, 139, 158)
    private val borderColor = Color.rgb(232, 234, 241)

    companion object {
        private const val PREFS_PREFIX = "money_tracker_expenses_"
        private const val PREFS_RECORDS_KEY = "expense_records_json"
        private const val PERIOD_15TH = "15th_salary"
        private const val PERIOD_MONTH_END = "month_end_salary"
    }

    // =====================================================
    // ACTIVITY LIFECYCLE
    // =====================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_expenses)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        initializeViews()
        setupWindowInsets()
        initializeMonth()
        setupSpinners()
        setupClickListeners()
        setupSearch()

        checkUserSession()
    }

    override fun onDestroy() {
        expenseListener?.remove()
        expenseListener = null
        super.onDestroy()
    }

    // =====================================================
    // VIEW INITIALIZATION
    // =====================================================

    private fun initializeViews() {
        tvExpenseMonth = findViewById(R.id.tvExpenseMonth)
        tvTotalExpenseAmount = findViewById(R.id.tvTotalExpenseAmount)
        tvExpenseMonthlyComparison = findViewById(R.id.tvExpenseMonthlyComparison)
        tvExpenseCount = findViewById(R.id.tvExpenseCount)
        tvAverageExpense = findViewById(R.id.tvAverageExpense)
        tvLargestExpense = findViewById(R.id.tvLargestExpense)
        tvExpenseFilterSummary = findViewById(R.id.tvExpenseFilterSummary)
        tvExpenseRecordCount = findViewById(R.id.tvExpenseRecordCount)
        tvExpenseCategoryEmpty = findViewById(R.id.tvExpenseCategoryEmpty)
        expenseCategoryBreakdown = findViewById(R.id.expenseCategoryBreakdown)
        expenseListContainer = findViewById(R.id.expenseListContainer)
        expenseEmptyState = findViewById(R.id.expenseEmptyState)
        expenseLoading = findViewById(R.id.expenseLoading)
        tvExpenseError = findViewById(R.id.tvExpenseError)
        etExpenseSearch = findViewById(R.id.etExpenseSearch)
        spExpenseCategory = findViewById(R.id.spExpenseCategory)
        spExpensePaydayPeriod = findViewById(R.id.spExpensePaydayPeriod)
    }

    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.main)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
    }

    private fun initializeMonth() {
        monthCalendar.timeInMillis = System.currentTimeMillis()
        monthCalendar.set(Calendar.DAY_OF_MONTH, 1)
        monthCalendar.set(Calendar.HOUR_OF_DAY, 0)
        monthCalendar.set(Calendar.MINUTE, 0)
        monthCalendar.set(Calendar.SECOND, 0)
        monthCalendar.set(Calendar.MILLISECOND, 0)
        updateMonthLabel()
    }

    // =====================================================
    // AUTHENTICATION AND USER SESSION
    // =====================================================

    private fun checkUserSession() {
        val user = auth.currentUser
        if (user == null) {
            navigateToLogin()
            return
        }
        if (!user.isEmailVerified) {
            navigateToEmailVerification()
            return
        }

        userId = user.uid
        preferences = getSharedPreferences(PREFS_PREFIX + userId, Context.MODE_PRIVATE)

        // Local data is rendered immediately; Firestore then refreshes it.
        loadLocalExpenses()
        renderAll()
        startExpenseSnapshotListener()

        if (intent.getBooleanExtra("open_form", false)) {
            findViewById<View>(R.id.main).post { showExpenseForm(initialCategory = selectedQuickCategory) }
            intent.removeExtra("open_form")
        }
    }

    private fun navigateToLogin() {
        if (isNavigatingAway || isFinishing) return
        isNavigatingAway = true
        startActivity(Intent(this, Login::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun navigateToEmailVerification() {
        if (isNavigatingAway || isFinishing) return
        isNavigatingAway = true
        startActivity(Intent(this, EmailVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // =====================================================
    // FIRESTORE + LOCAL CACHE
    // =====================================================

    private fun startExpenseSnapshotListener() {
        expenseListener?.remove()
        val user = auth.currentUser ?: return
        setLoading(true)
        expenseListener = db.collection("users")
            .document(user.uid)
            .collection("expenses")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, exception ->
                if (exception != null) {
                    setLoading(false)
                    showError("Cloud sync is unavailable. Showing saved device records. ${exception.localizedMessage.orEmpty()}")
                    renderAll()
                    return@addSnapshotListener
                }
                if (snapshot == null) {
                    setLoading(false)
                    return@addSnapshotListener
                }

                val cloudRecords = snapshot.documents.mapNotNull { documentToExpense(it) }
                val isAuthoritative = !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()

                if (isAuthoritative) {
                    expenses.clear()
                    expenses.addAll(cloudRecords)
                    hasReceivedCloudSnapshot = true
                } else {
                    // A cache snapshot may contain only documents previously fetched by Firestore.
                    // Merge it with SharedPreferences so locally cached history is not discarded.
                    mergeExpenses(cloudRecords)
                }

                saveLocalExpenses()
                setLoading(false)
                clearError()
                renderAll()

                if (snapshot.metadata.isFromCache && !hasReceivedCloudSnapshot) {
                    tvExpenseFilterSummary.text = "Showing locally saved expenses. Cloud updates will appear when connected."
                }
            }
    }

    private fun mergeExpenses(incoming: List<ExpenseRecord>) {
        val merged = LinkedHashMap<String, ExpenseRecord>()
        expenses.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        expenses.clear()
        expenses.addAll(merged.values)
    }

    private fun documentToExpense(document: DocumentSnapshot): ExpenseRecord? {
        val amount = amountFrom(document, "amount", "total")
        val description = stringFrom(document, "description", "name").ifBlank { "Expense" }
        val category = stringFrom(document, "category").ifBlank { "Other" }
        val date = dateFrom(document) ?: return null
        val savedPeriod = stringFrom(document, "paydayPeriod", "paydayPeriodLabel")
        val period = when {
            savedPeriod.contains("15", ignoreCase = true) -> PERIOD_15TH
            savedPeriod.contains("month_end", ignoreCase = true) ||
                    savedPeriod.contains("month-end", ignoreCase = true) ||
                    savedPeriod.contains("month end", ignoreCase = true) -> PERIOD_MONTH_END
            else -> paydayPeriodFor(date)
        }
        val createdAt = dateFromFields(document, "createdAt", "date", "timestamp")?.time ?: date.time
        return ExpenseRecord(
            id = document.id,
            description = description,
            amount = amount,
            category = category,
            dateMillis = date.time,
            paydayPeriod = period,
            paymentMethod = stringFrom(document, "paymentMethod", "method"),
            reference = stringFrom(document, "reference", "referenceNumber"),
            notes = stringFrom(document, "notes", "note"),
            createdAtMillis = createdAt
        )
    }

    private fun loadLocalExpenses() {
        expenses.clear()
        val raw = preferences.getString(PREFS_RECORDS_KEY, "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val dateMillis = item.optLong("dateMillis", 0L)
                val id = item.optString("id")
                if (id.isBlank() || dateMillis <= 0L) continue
                expenses.add(
                    ExpenseRecord(
                        id = id,
                        description = item.optString("description", "Expense"),
                        amount = item.optDouble("amount", 0.0),
                        category = item.optString("category", "Other"),
                        dateMillis = dateMillis,
                        paydayPeriod = item.optString("paydayPeriod", paydayPeriodFor(Date(dateMillis))),
                        paymentMethod = item.optString("paymentMethod", ""),
                        reference = item.optString("reference", ""),
                        notes = item.optString("notes", ""),
                        createdAtMillis = item.optLong("createdAtMillis", dateMillis)
                    )
                )
            }
        } catch (exception: Exception) {
            showError("Some locally saved expense data could not be read: ${exception.localizedMessage.orEmpty()}")
        }
        expenses.sortByDescending { it.dateMillis }
    }

    private fun saveLocalExpenses() {
        val array = JSONArray()
        expenses.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("description", record.description)
                put("amount", record.amount)
                put("category", record.category)
                put("dateMillis", record.dateMillis)
                put("paydayPeriod", record.paydayPeriod)
                put("paymentMethod", record.paymentMethod)
                put("reference", record.reference)
                put("notes", record.notes)
                put("createdAtMillis", record.createdAtMillis)
            })
        }
        preferences.edit().putString(PREFS_RECORDS_KEY, array.toString()).apply()
    }

    private fun writeExpenseToCloud(record: ExpenseRecord, isNew: Boolean) {
        val user = auth.currentUser ?: return
        val data = hashMapOf<String, Any>(
            "description" to record.description,
            "amount" to record.amount,
            "category" to record.category,
            "type" to "expense",
            "date" to Timestamp(Date(record.dateMillis)),
            "dateString" to formatDateKey(Date(record.dateMillis)),
            "paydayPeriod" to record.paydayPeriod,
            "paydayPeriodLabel" to record.paydayLabel,
            "paymentMethod" to record.paymentMethod,
            "reference" to record.reference,
            "notes" to record.notes,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (isNew) {
            data["createdAt"] = FieldValue.serverTimestamp()
        } else {
            data["createdAt"] = Timestamp(Date(record.createdAtMillis))
        }

        // Firestore Android queues writes while offline; this module also keeps its own
        // SharedPreferences copy for immediate display and an independent device cache.
        db.collection("users").document(user.uid)
            .collection("expenses").document(record.id)
            .set(data, SetOptions.merge())
            .addOnFailureListener { exception ->
                showError("Expense is saved on this device, but cloud sync failed: ${exception.localizedMessage.orEmpty()}")
            }
    }

    private fun deleteExpenseFromCloud(id: String) {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid)
            .collection("expenses").document(id)
            .delete()
            .addOnFailureListener { exception ->
                showError("Expense was removed locally, but cloud deletion failed: ${exception.localizedMessage.orEmpty()}")
            }
    }

    // =====================================================
    // SPINNERS AND FILTERS
    // =====================================================

    private fun setupSpinners() {
        val categoryOptions = listOf("All Categories") + categories
        spExpenseCategory.adapter = spinnerAdapter(categoryOptions)
        spExpensePaydayPeriod.adapter = spinnerAdapter(
            listOf("All Paydays", "15th salary period", "Month-end salary period")
        )

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderAll()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spExpenseCategory.onItemSelectedListener = listener
        spExpensePaydayPeriod.onItemSelectedListener = listener
    }

    private fun spinnerAdapter(values: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupSearch() {
        etExpenseSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderAll() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun getMonthExpenses(): List<ExpenseRecord> {
        return expenses.filter { isSameMonth(it.date, monthCalendar) }
    }

    private fun getFilteredExpenses(): List<ExpenseRecord> {
        val query = etExpenseSearch.text.toString().trim().lowercase(Locale.ROOT)
        val category = spExpenseCategory.selectedItem?.toString().orEmpty()
        val period = spExpensePaydayPeriod.selectedItem?.toString().orEmpty()
        return getMonthExpenses().filter { record ->
            val categoryMatches = category.isBlank() || category == "All Categories" || record.category.equals(category, true)
            val periodMatches = period.isBlank() || period == "All Paydays" || record.paydayLabel.equals(period, true)
            val searchable = listOf(
                record.description, record.category, record.notes, record.paymentMethod,
                record.reference, record.amount.toString(), formatDate(record.date), record.paydayLabel
            ).joinToString(" ").lowercase(Locale.ROOT)
            categoryMatches && periodMatches && searchable.contains(query)
        }.sortedByDescending { it.dateMillis }
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnExpensesBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddExpense).setOnClickListener { showExpenseForm(initialCategory = selectedQuickCategory) }
        findViewById<View>(R.id.btnOpenExpenseForm).setOnClickListener { showExpenseForm(initialCategory = selectedQuickCategory) }

        findViewById<View>(R.id.btnPreviousExpenseMonth).setOnClickListener {
            monthCalendar.add(Calendar.MONTH, -1)
            updateMonthLabel()
            renderAll()
        }
        findViewById<View>(R.id.btnNextExpenseMonth).setOnClickListener {
            val next = monthCalendar.clone() as Calendar
            next.add(Calendar.MONTH, 1)
            val now = Calendar.getInstance(timezone)
            val isFuture = next.get(Calendar.YEAR) > now.get(Calendar.YEAR) ||
                    (next.get(Calendar.YEAR) == now.get(Calendar.YEAR) && next.get(Calendar.MONTH) > now.get(Calendar.MONTH))
            if (isFuture) {
                showToast("Future expense months are not available yet.")
            } else {
                monthCalendar.add(Calendar.MONTH, 1)
                updateMonthLabel()
                renderAll()
            }
        }
        findViewById<View>(R.id.btnClearExpenseFilters).setOnClickListener {
            etExpenseSearch.setText("")
            spExpenseCategory.setSelection(0)
            spExpensePaydayPeriod.setSelection(0)
        }

        quickCategoryViews.forEach { (viewId, category) ->
            findViewById<View>(viewId).setOnClickListener {
                selectedQuickCategory = category
                updateQuickCategoryStyles()
                showExpenseForm(initialCategory = category)
            }
        }
        updateQuickCategoryStyles()
    }

    private fun updateQuickCategoryStyles() {
        quickCategoryViews.forEach { (viewId, category) ->
            val chip = findViewById<TextView>(viewId)
            val selected = selectedQuickCategory == category
            chip.background = roundedBackground(
                if (selected) purple else Color.WHITE,
                if (selected) purple else borderColor,
                20
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.rgb(80, 85, 107))
            chip.isSelected = selected
        }
    }

    // =====================================================
    // ADD / EDIT EXPENSE FORM
    // =====================================================

    private fun showExpenseForm(id: String? = null, initialCategory: String? = null) {
        val existing = id?.let { recordId -> expenses.firstOrNull { it.id == recordId } }
        if (id != null && existing == null) return

        val formScroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(10))
        }
        formScroll.addView(form)

        val descriptionInput = createEditText("What did you spend on?", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        descriptionInput.setText(existing?.description.orEmpty())

        val amountInput = createEditText("0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        if (existing != null) amountInput.setText(existing.amount.toString())

        val categorySpinner = Spinner(this)
        categorySpinner.adapter = spinnerAdapter(categories)
        val initialCategoryValue = existing?.category ?: initialCategory ?: selectedQuickCategory
        val categoryIndex = categories.indexOfFirst { it.equals(initialCategoryValue, true) }.coerceAtLeast(0)
        categorySpinner.setSelection(categoryIndex)

        val paymentInput = createEditText("Cash, GCash, bank transfer, card...", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        paymentInput.setText(existing?.paymentMethod.orEmpty())

        val referenceInput = createEditText("Optional reference number", InputType.TYPE_CLASS_TEXT)
        referenceInput.setText(existing?.reference.orEmpty())

        val notesInput = createEditText("Additional details (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        notesInput.setText(existing?.notes.orEmpty())

        var expenseDate = existing?.date ?: Date()
        val dateButton = TextView(this).apply {
            text = formatDate(expenseDate)
            setTextColor(darkText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            background = roundedBackground(Color.WHITE, borderColor, 10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showExpenseDatePicker(expenseDate) { chosen ->
                    expenseDate = chosen
                    text = formatDate(chosen)
                }
            }
        }

        form.addView(formLabel("Description *"))
        form.addView(descriptionInput, formInputParams())
        form.addView(formLabel("Amount (₱) *"))
        form.addView(amountInput, formInputParams())
        form.addView(formLabel("Category *"))
        form.addView(categorySpinner, formInputParams())
        form.addView(formLabel("Expense date *"))
        form.addView(dateButton)
        form.addView(formLabel("Payment method (optional)"))
        form.addView(paymentInput, formInputParams())
        form.addView(formLabel("Reference (optional)"))
        form.addView(referenceInput, formInputParams())
        form.addView(formLabel("Notes (optional)"))
        form.addView(notesInput, formInputParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Expense" else "Edit Expense")
            .setView(formScroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Save Expense" else "Update Expense", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val description = descriptionInput.text.toString().trim()
                val amount = parseAmountOrNull(amountInput.text.toString())
                val category = categorySpinner.selectedItem?.toString().orEmpty()

                if (description.isBlank()) {
                    descriptionInput.error = "Enter an expense description"
                    descriptionInput.requestFocus()
                    return@setOnClickListener
                }
                if (amount == null || amount <= 0.0 || amount > 999_999_999.99) {
                    amountInput.error = "Enter a valid amount greater than zero"
                    amountInput.requestFocus()
                    return@setOnClickListener
                }
                if (category.isBlank()) {
                    showToast("Please select an expense category.")
                    return@setOnClickListener
                }

                val now = System.currentTimeMillis()
                val record = ExpenseRecord(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    description = description,
                    amount = amount,
                    category = category,
                    dateMillis = expenseDate.time,
                    paydayPeriod = paydayPeriodFor(expenseDate),
                    paymentMethod = paymentInput.text.toString().trim(),
                    reference = referenceInput.text.toString().trim(),
                    notes = notesInput.text.toString().trim(),
                    createdAtMillis = existing?.createdAtMillis ?: now
                )

                saveExpenseLocallyAndCloud(record, isNew = existing == null)
                dialog.dismiss()
                hideKeyboard()
            }
        }
        dialog.show()
    }

    private fun createEditText(hintText: String, inputTypeValue: Int): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = inputTypeValue
            setTextColor(darkText)
            setHintTextColor(mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = roundedBackground(Color.WHITE, borderColor, 10)
            layoutParams = formInputParams()
        }
    }

    private fun formLabel(value: String): TextView {
        return TextView(this).apply {
            text = value
            setTextColor(darkText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun formInputParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(49)
        ).apply { bottomMargin = dp(2) }
    }

    private fun showExpenseDatePicker(current: Date, onSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance(timezone).apply { time = current }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance(timezone)
                chosen.clear()
                chosen.set(year, month, day, 12, 0, 0)
                onSelected(chosen.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveExpenseLocallyAndCloud(record: ExpenseRecord, isNew: Boolean) {
        val index = expenses.indexOfFirst { it.id == record.id }
        if (index >= 0) expenses[index] = record else expenses.add(record)
        expenses.sortByDescending { it.dateMillis }
        saveLocalExpenses()
        renderAll()
        writeExpenseToCloud(record, isNew)
        showToast(if (isNew) "Expense saved on this device and queued for cloud sync." else "Expense updated on this device and queued for cloud sync.")
    }

    // =====================================================
    // DELETE EXPENSE
    // =====================================================

    private fun confirmDeleteExpense(record: ExpenseRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Delete '${record.description}' (${formatCurrency(record.amount)})? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                expenses.removeAll { it.id == record.id }
                saveLocalExpenses()
                renderAll()
                deleteExpenseFromCloud(record.id)
                showToast("Expense removed from this device and cloud deletion queued.")
            }
            .show()
    }

    // =====================================================
    // RENDER DASHBOARD
    // =====================================================

    private fun renderAll() {
        if (!::tvExpenseMonth.isInitialized) return
        updateMonthLabel()
        val monthRecords = getMonthExpenses()
        val filtered = getFilteredExpenses()
        updateStatistics(filtered, monthRecords)
        renderCategoryBreakdown(filtered)
        renderExpenseRecords(filtered)
        updateFilterSummary(filtered.size, monthRecords.size)
    }

    private fun updateMonthLabel() {
        tvExpenseMonth.text = SimpleDateFormat("MMMM yyyy", Locale.US).apply {
            timeZone = timezone
        }.format(monthCalendar.time)
    }

    private fun updateStatistics(filtered: List<ExpenseRecord>, monthRecords: List<ExpenseRecord>) {
        val total = filtered.sumOf { it.amount }
        val average = if (filtered.isNotEmpty()) total / filtered.size else 0.0
        val largest = filtered.maxByOrNull { it.amount }

        tvTotalExpenseAmount.text = formatCurrency(total)
        tvExpenseCount.text = filtered.size.toString()
        tvAverageExpense.text = formatCurrency(average)
        tvLargestExpense.text = largest?.let { formatCurrency(it.amount) } ?: formatCurrency(0.0)

        val previousMonth = getCalendar(monthCalendar.time).apply { add(Calendar.MONTH, -1) }
        val previousTotal = expenses.filter { isSameMonth(it.date, previousMonth) }.sumOf { it.amount }
        val comparison = when {
            previousTotal <= 0.0 && total <= 0.0 -> "No spending recorded in either period"
            previousTotal <= 0.0 -> "No spending recorded last month"
            else -> {
                val change = ((total - previousTotal) / previousTotal) * 100.0
                val direction = if (change > 0.05) "higher" else if (change < -0.05) "lower" else "about the same"
                if (direction == "about the same") "About the same as last month" else "${String.format(Locale.US, "%.1f", kotlin.math.abs(change))}% $direction than last month"
            }
        }
        tvExpenseMonthlyComparison.text = comparison
    }

    private fun renderCategoryBreakdown(records: List<ExpenseRecord>) {
        expenseCategoryBreakdown.removeAllViews()
        val grouped = records.groupBy { it.category }
            .mapValues { (_, items) -> items.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }

        tvExpenseCategoryEmpty.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
        if (grouped.isEmpty()) {
            // Keep the empty label in its original parent after rebuilding the container.
            expenseCategoryBreakdown.addView(tvExpenseCategoryEmpty)
            return
        }

        val maximum = grouped.maxOf { it.second }.coerceAtLeast(1.0)
        grouped.forEach { (category, amount) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labels.addView(makeText(category, 11f, darkText, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            labels.addView(makeText(formatCurrency(amount), 11f, purple, true))
            row.addView(labels)

            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                this.progress = ((amount / maximum) * 1000).toInt().coerceIn(0, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(purple)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(239, 239, 246))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(7) }
            }
            row.addView(progress)
            expenseCategoryBreakdown.addView(row)
        }
    }

    private fun renderExpenseRecords(records: List<ExpenseRecord>) {
        expenseListContainer.removeAllViews()
        expenseEmptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        expenseListContainer.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        tvExpenseRecordCount.text = "${records.size} record${if (records.size == 1) "" else "s"}"
        records.forEach { expenseListContainer.addView(createExpenseCard(it)) }
    }

    private fun createExpenseCard(record: ExpenseRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, borderColor, 13)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(makeText(record.category, 12f, purple, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(makeText(formatDate(record.date), 10f, mutedText))
        card.addView(top)

        card.addView(makeText(record.description, 14f, darkText, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        card.addView(makeText(formatCurrency(record.amount), 21f, red, true).apply {
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(financialDetailRow("Payday period", record.paydayLabel))
        if (record.paymentMethod.isNotBlank()) card.addView(financialDetailRow("Payment method", record.paymentMethod))
        if (record.reference.isNotBlank()) card.addView(financialDetailRow("Reference", record.reference))
        if (record.notes.isNotBlank()) {
            card.addView(makeText(record.notes, 10f, mutedText).apply { setPadding(0, dp(8), 0, dp(3)) })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
        }
        actions.addView(actionButton("Edit", Color.rgb(239, 237, 255), purple) { showExpenseForm(record.id) })
        actions.addView(actionButton("Delete", Color.rgb(255, 241, 241), red) { confirmDeleteExpense(record) }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
        })
        card.addView(actions)
        return card
    }

    private fun financialDetailRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(makeText(label, 10f, mutedText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(makeText(value, 10f, darkText, true))
        return row
    }

    private fun actionButton(label: String, fill: Int, textColor: Int, action: () -> Unit): TextView {
        return makeText(label, 10f, textColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = roundedBackground(fill, fill, 9)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun updateFilterSummary(filteredCount: Int, monthCount: Int) {
        val category = spExpenseCategory.selectedItem?.toString().orEmpty()
        val period = spExpensePaydayPeriod.selectedItem?.toString().orEmpty()
        val query = etExpenseSearch.text.toString().trim()
        val filtersActive = category != "All Categories" || period != "All Paydays" || query.isNotBlank()
        tvExpenseFilterSummary.text = when {
            monthCount == 0 -> "No expenses recorded for the selected month."
            filteredCount == 0 -> "No expenses match the selected filters."
            filtersActive -> "Showing $filteredCount of $monthCount expenses for this month."
            else -> "Showing all $monthCount expenses for the selected month."
        }
    }

    // =====================================================
    // FIRESTORE FIELD HELPERS
    // =====================================================

    private fun amountFrom(document: DocumentSnapshot, vararg fields: String): Double {
        for (field in fields) {
            if (!document.contains(field)) continue
            return parseAmount(document.get(field))
        }
        return 0.0
    }

    private fun stringFrom(document: DocumentSnapshot, vararg fields: String): String {
        for (field in fields) {
            val value = document.get(field)
            if (value is String && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun dateFrom(document: DocumentSnapshot): Date? = dateFromFields(
        document,
        "date", "transactionDate", "expenseDate", "dateString", "createdAt", "timestamp"
    )

    private fun dateFromFields(document: DocumentSnapshot, vararg fields: String): Date? {
        for (field in fields) {
            when (val value = document.get(field)) {
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
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "MMM d, yyyy", "MMMM d, yyyy")
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = timezone
                }
                formatter.parse(value)?.let { return it }
            } catch (_: Exception) { /* Try another date format. */ }
        }
        return null
    }

    private fun parseAmount(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> {
                val trimmed = value.trim()
                val negative = trimmed.startsWith("(") && trimmed.endsWith(")")
                val parsed = trimmed.replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull() ?: 0.0
                if (negative && parsed > 0.0) -parsed else parsed
            }
            else -> 0.0
        }
    }

    private fun parseAmountOrNull(value: String): Double? {
        val cleaned = value.trim().replace(",", "").replace("₱", "").trim()
        if (cleaned.isBlank()) return null
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    // =====================================================
    // DATE AND FORMATTING HELPERS
    // =====================================================

    private fun getCalendar(date: Date): Calendar = Calendar.getInstance(timezone).apply { time = date }

    private fun paydayPeriodFor(date: Date): String {
        return if (getCalendar(date).get(Calendar.DAY_OF_MONTH) >= 15) PERIOD_15TH else PERIOD_MONTH_END
    }

    private fun isSameMonth(date: Date, month: Calendar): Boolean {
        val value = getCalendar(date)
        return value.get(Calendar.YEAR) == month.get(Calendar.YEAR) && value.get(Calendar.MONTH) == month.get(Calendar.MONTH)
    }

    private fun formatDate(date: Date): String = SimpleDateFormat("dd MMM yyyy", Locale.US).apply { timeZone = timezone }.format(date)

    private fun formatDateKey(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = timezone }.format(date)

    private fun formatCurrency(amount: Double): String = currencyFormatter.format(amount)

    // =====================================================
    // UI HELPERS
    // =====================================================

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun setLoading(loading: Boolean) {
        expenseLoading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading && expenses.isEmpty()) {
            expenseEmptyState.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        tvExpenseError.text = message
        tvExpenseError.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvExpenseError.text = ""
        tvExpenseError.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

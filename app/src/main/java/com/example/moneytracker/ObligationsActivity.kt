
package com.example.moneytracker

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class ObligationsActivity : AppCompatActivity() {

    // ==========================================
    // DATA MODELS
    // ==========================================

    private data class Obligation(
        var id: String,
        var type: String,
        var payee: String,
        var description: String,
        var amount: Double,
        var paidAmount: Double,
        var dueDate: Long,
        var repeat: String,
        var notes: String
    ) {
        val remaining: Double
            get() = (amount - paidAmount).coerceAtLeast(0.0)
    }

    private data class Payment(
        var id: String,
        var obligationId: String,
        var obligationName: String,
        var amount: Double,
        var paymentDate: Long,
        var method: String,
        var principal: Double,
        var interest: Double,
        var fees: Double,
        var reference: String,
        var notes: String
    )

    private val obligations = mutableListOf<Obligation>()
    private val payments = mutableListOf<Payment>()

    private var paymentOptions = listOf<Obligation>()

    private var selectedDueDate: Long? = null
    private var selectedPaymentDate: Long? = null
    private var selectedPaymentObligationId: String? = null

    private lateinit var prefs: SharedPreferences

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var userId: String = ""

    private val obligationsCollection
        get() = firestore.collection("users")
            .document(userId)
            .collection("obligations")

    private val paymentsCollection
        get() = firestore.collection("users")
            .document(userId)
            .collection("payments")

    private var obligationsListener: ListenerRegistration? = null
    private var paymentsListener: ListenerRegistration? = null

    private var obligationsLoaded = false
    private var paymentsLoaded = false

    // Existing records from the previous device-only version are migrated
    // into the first authenticated user's Firebase account.
    private var legacyMigrationPending = false
    private var remoteObligationsServerLoaded = false
    private var remotePaymentsServerLoaded = false

    private val currencyFormatter =
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    private val dateFormatter =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // ==========================================
    // FORM VIEWS
    // ==========================================

    private lateinit var obligationFormCard: View
    private lateinit var paymentFormCard: View

    private lateinit var etObligationId: EditText
    private lateinit var etObligationPayee: EditText
    private lateinit var etObligationDescription: EditText
    private lateinit var etObligationAmount: EditText
    private lateinit var etObligationNotes: EditText

    private lateinit var etPaymentAmount: EditText
    private lateinit var etPaymentPrincipal: EditText
    private lateinit var etPaymentInterest: EditText
    private lateinit var etPaymentFees: EditText
    private lateinit var etPaymentReference: EditText
    private lateinit var etPaymentNotes: EditText

    private lateinit var spObligationType: Spinner
    private lateinit var spObligationRepeat: Spinner
    private lateinit var spObligationStatusFilter: Spinner
    private lateinit var spObligationTypeFilter: Spinner
    private lateinit var spPaymentMethod: Spinner
    private lateinit var spPaymentObligation: Spinner

    private lateinit var tvObligationDueDate: TextView
    private lateinit var tvPaymentDate: TextView

    private lateinit var tvObligationFormError: TextView
    private lateinit var tvPaymentFormError: TextView

    private lateinit var tvPaymentOutstandingBalance: TextView
    private lateinit var tvPaymentObligationInfo: TextView

    // ==========================================
    // ACTIVITY
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_obligations)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            openLogin()
            return
        }

        if (!currentUser.isEmailVerified) {
            Toast.makeText(
                this,
                "Please verify your email before managing financial records.",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(this, EmailVerificationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        userId = currentUser.uid
        prefs = getSharedPreferences(
            "money_tracker_obligations_$userId",
            MODE_PRIVATE
        )

        setupWindowInsets()
        initializeViews()
        loadLocalData()
        setupSpinners()
        setupClickListeners()
        setupSearchAndFilters()

        renderAll()
        startFirestoreListeners()
    }

    private fun openLogin() {
        startActivity(
            Intent(this, Login::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    override fun onDestroy() {
        obligationsListener?.remove()
        paymentsListener?.remove()
        super.onDestroy()
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

        obligationFormCard = findViewById(
            R.id.obligationFormCard
        )

        paymentFormCard = findViewById(
            R.id.paymentFormCard
        )

        etObligationId = findViewById(
            R.id.etObligationId
        )

        etObligationPayee = findViewById(
            R.id.etObligationPayee
        )

        etObligationDescription = findViewById(
            R.id.etObligationDescription
        )

        etObligationAmount = findViewById(
            R.id.etObligationAmount
        )

        etObligationNotes = findViewById(
            R.id.etObligationNotes
        )

        etPaymentAmount = findViewById(
            R.id.etPaymentAmount
        )

        etPaymentPrincipal = findViewById(
            R.id.etPaymentPrincipal
        )

        etPaymentInterest = findViewById(
            R.id.etPaymentInterest
        )

        etPaymentFees = findViewById(
            R.id.etPaymentFees
        )

        etPaymentReference = findViewById(
            R.id.etPaymentReference
        )

        etPaymentNotes = findViewById(
            R.id.etPaymentNotes
        )

        spObligationType = findViewById(
            R.id.spObligationType
        )

        spObligationRepeat = findViewById(
            R.id.spObligationRepeat
        )

        spObligationStatusFilter = findViewById(
            R.id.spObligationStatusFilter
        )

        spObligationTypeFilter = findViewById(
            R.id.spObligationTypeFilter
        )

        spPaymentMethod = findViewById(
            R.id.spPaymentMethod
        )

        spPaymentObligation = findViewById(
            R.id.spPaymentObligation
        )

        tvObligationDueDate = findViewById(
            R.id.tvObligationDueDate
        )

        tvPaymentDate = findViewById(
            R.id.tvPaymentDate
        )

        tvObligationFormError = findViewById(
            R.id.tvObligationFormError
        )

        tvPaymentFormError = findViewById(
            R.id.tvPaymentFormError
        )

        tvPaymentOutstandingBalance = findViewById(
            R.id.tvPaymentOutstandingBalance
        )

        tvPaymentObligationInfo = findViewById(
            R.id.tvPaymentObligationInfo
        )
    }

    // ==========================================
    // SPINNERS
    // ==========================================

    private fun setupSpinners() {

        setSpinnerItems(
            spObligationType,
            resources.getStringArray(
                R.array.obligation_types
            ).toList()
        )

        setSpinnerItems(
            spObligationRepeat,
            resources.getStringArray(
                R.array.obligation_repeat_options
            ).toList()
        )

        setSpinnerItems(
            spPaymentMethod,
            resources.getStringArray(
                R.array.payment_methods
            ).toList()
        )

        setSpinnerItems(
            spObligationStatusFilter,
            listOf(
                "All Statuses",
                "Upcoming",
                "Due Today",
                "Overdue",
                "Partially Paid",
                "Paid"
            )
        )

        val types = resources.getStringArray(
            R.array.obligation_types
        ).toList()

        setSpinnerItems(
            spObligationTypeFilter,
            listOf("All Types") + types
        )

        setSpinnerItems(
            spPaymentObligation,
            listOf("Select a bill / payment")
        )

        spPaymentObligation.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    selectedPaymentObligationId =
                        paymentOptions.getOrNull(position)?.id

                    updatePaymentOutstanding()
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }

        val filterListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    renderObligations()
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }

        spObligationStatusFilter.onItemSelectedListener =
            filterListener

        spObligationTypeFilter.onItemSelectedListener =
            filterListener
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

        findViewById<View>(
            R.id.btnObligationsBack
        ).setOnClickListener {
            finish()
        }

        findViewById<View>(
            R.id.btnAddObligation
        ).setOnClickListener {
            showObligationForm()
        }

        findViewById<View>(
            R.id.btnCloseObligationForm
        ).setOnClickListener {
            obligationFormCard.visibility = View.GONE
        }

        findViewById<View>(
            R.id.btnClosePaymentForm
        ).setOnClickListener {
            paymentFormCard.visibility = View.GONE
        }

        findViewById<View>(
            R.id.btnSaveObligation
        ).setOnClickListener {
            saveObligation()
        }

        findViewById<View>(
            R.id.btnSavePayment
        ).setOnClickListener {
            savePayment()
        }

        tvObligationDueDate.setOnClickListener {
            showObligationDatePicker()
        }

        tvPaymentDate.setOnClickListener {
            showPaymentDatePicker()
        }

        // Clear filters

        findViewById<View>(
            R.id.btnClearObligationFilters
        ).setOnClickListener {

            findViewById<EditText>(
                R.id.etObligationSearch
            ).setText("")

            spObligationStatusFilter.setSelection(0)
            spObligationTypeFilter.setSelection(0)

            renderObligations()
        }

        // Common bill presets

        findViewById<View>(
            R.id.chipElectricity
        ).setOnClickListener {
            applyBillPreset("Electricity bill")
        }

        findViewById<View>(
            R.id.chipInternet
        ).setOnClickListener {
            applyBillPreset("Internet bill")
        }

        findViewById<View>(
            R.id.chipWater
        ).setOnClickListener {
            applyBillPreset("Water bill")
        }

        findViewById<View>(
            R.id.chipMobile
        ).setOnClickListener {
            applyBillPreset("Mobile phone bill")
        }

        findViewById<View>(
            R.id.chipRent
        ).setOnClickListener {
            applyBillPreset("Monthly rent")
        }
    }

    // ==========================================
    // SEARCH AND FILTERS
    // ==========================================

    private fun setupSearchAndFilters() {

        val search = findViewById<EditText>(
            R.id.etObligationSearch
        )

        search.addTextChangedListener(
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
                    renderObligations()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {}
            }
        )
    }

    private fun getFilteredObligations(): List<Obligation> {

        val search = findViewById<EditText>(
            R.id.etObligationSearch
        ).text.toString().trim().lowercase()

        val selectedStatus =
            spObligationStatusFilter.selectedItem?.toString()
                ?: "All Statuses"

        val selectedType =
            spObligationTypeFilter.selectedItem?.toString()
                ?: "All Types"

        return obligations.filter { obligation ->

            val matchesSearch =
                obligation.payee.lowercase().contains(search) ||
                        obligation.description.lowercase().contains(search) ||
                        obligation.type.lowercase().contains(search)

            val matchesStatus =
                selectedStatus == "All Statuses" ||
                        selectedStatus == getStatus(obligation) ||
                        (
                                selectedStatus == "Partially Paid" &&
                                        isPartiallyPaid(obligation)
                                )

            val matchesType =
                selectedType == "All Types" ||
                        obligation.type == selectedType

            matchesSearch && matchesStatus && matchesType
        }.sortedBy { it.dueDate }
    }

    // ==========================================
    // OBLIGATION FORM
    // ==========================================

    private fun showObligationForm(id: String? = null) {

        tvObligationFormError.visibility = View.GONE

        if (id == null) {

            clearObligationForm()

            findViewById<TextView>(
                R.id.tvObligationFormTitle
            ).text = "Add Bill / Scheduled Payment"

        } else {

            val item = obligations.find {
                it.id == id
            } ?: return

            etObligationId.setText(item.id)
            etObligationPayee.setText(item.payee)
            etObligationDescription.setText(item.description)
            etObligationAmount.setText(item.amount.toString())
            etObligationNotes.setText(item.notes)

            selectedDueDate = item.dueDate

            tvObligationDueDate.text =
                formatDate(item.dueDate)

            setSpinnerSelection(spObligationType, item.type)
            setSpinnerSelection(spObligationRepeat, item.repeat)

            findViewById<TextView>(
                R.id.tvObligationFormTitle
            ).text = "Edit Payment Schedule"
        }

        obligationFormCard.visibility = View.VISIBLE

        obligationFormCard.post {
            findViewById<android.widget.ScrollView>(
                R.id.obligationsScroll
            ).smoothScrollTo(0, obligationFormCard.top)
        }
    }

    private fun clearObligationForm() {

        etObligationId.setText("")
        etObligationPayee.setText("")
        etObligationDescription.setText("")
        etObligationAmount.setText("")
        etObligationNotes.setText("")

        selectedDueDate = null

        tvObligationDueDate.text = "Select due date"

        if (spObligationType.count > 0) {
            spObligationType.setSelection(0)
        }

        if (spObligationRepeat.count > 0) {
            spObligationRepeat.setSelection(0)
        }
    }

    private fun applyBillPreset(description: String) {

        if (obligationFormCard.visibility != View.VISIBLE) {
            showObligationForm()
        }

        etObligationDescription.setText(description)

        etObligationPayee.requestFocus()

        Toast.makeText(
            this,
            "$description selected. Enter your provider.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveObligation() {

        val payee = etObligationPayee.text.toString().trim()
        val description =
            etObligationDescription.text.toString().trim()

        val amount = getAmount(etObligationAmount)
        val dueDate = selectedDueDate

        val type = spObligationType.selectedItem?.toString()
            ?: ""

        val repeat = spObligationRepeat.selectedItem?.toString()
            ?: "One-time"

        val notes = etObligationNotes.text.toString().trim()

        val editingId = etObligationId.text.toString().trim()

        val existing = obligations.find {
            it.id == editingId && editingId.isNotBlank()
        }

        val error = when {

            type.isBlank() ->
                "Please select a payment type."

            payee.isBlank() ->
                "Please enter the payee or provider."

            description.isBlank() ->
                "Please enter a payment description."

            amount <= 0.0 ->
                "Please enter an amount greater than zero."

            dueDate == null ->
                "Please select a due date."

            existing != null && amount < existing.paidAmount ->
                "The amount cannot be lower than the amount already paid."

            else -> null
        }

        if (error != null) {
            showObligationError(error)
            return
        }

        if (existing != null) {

            existing.type = type
            existing.payee = payee
            existing.description = description
            existing.amount = amount
            existing.dueDate = dueDate!!
            existing.repeat = repeat
            existing.notes = notes

        } else {

            obligations.add(
                Obligation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    payee = payee,
                    description = description,
                    amount = amount,
                    paidAmount = 0.0,
                    dueDate = dueDate!!,
                    repeat = repeat,
                    notes = notes
                )
            )
        }

        val savedItem = if (existing != null) {
            existing
        } else {
            obligations.last()
        }

        saveLocalData()
        syncObligation(savedItem)

        obligationFormCard.visibility = View.GONE

        renderAll()

        Toast.makeText(
            this,
            "Payment schedule saved. Firebase will sync it when connected.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showObligationError(message: String) {

        tvObligationFormError.text = message
        tvObligationFormError.visibility = View.VISIBLE
    }

    // ==========================================
    // DATE PICKERS
    // ==========================================

    private fun showObligationDatePicker() {

        val calendar = Calendar.getInstance()

        selectedDueDate?.let {
            calendar.timeInMillis = it
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->

                val selected = Calendar.getInstance()

                selected.set(
                    year,
                    month,
                    day,
                    0,
                    0,
                    0
                )

                selected.set(Calendar.MILLISECOND, 0)

                selectedDueDate = selected.timeInMillis

                tvObligationDueDate.text =
                    formatDate(selected.timeInMillis)

                tvObligationFormError.visibility = View.GONE

            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showPaymentDatePicker() {

        val calendar = Calendar.getInstance()

        selectedPaymentDate?.let {
            calendar.timeInMillis = it
        }

        DatePickerDialog(
            this,
            { _, year, month, day ->

                val selected = Calendar.getInstance()

                selected.set(
                    year,
                    month,
                    day,
                    0,
                    0,
                    0
                )

                selected.set(Calendar.MILLISECOND, 0)

                selectedPaymentDate = selected.timeInMillis

                tvPaymentDate.text =
                    formatDate(selected.timeInMillis)

                tvPaymentFormError.visibility = View.GONE

            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ==========================================
    // PAYMENT FORM
    // ==========================================

    private fun showPaymentForm(id: String) {

        val item = obligations.find {
            it.id == id
        } ?: return

        if (item.remaining <= 0.009) {
            Toast.makeText(
                this,
                "This payment is already fully paid.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        selectedPaymentDate = startOfDay(
            System.currentTimeMillis()
        )

        tvPaymentDate.text =
            formatDate(selectedPaymentDate!!)

        etPaymentAmount.setText("")
        etPaymentPrincipal.setText("")
        etPaymentInterest.setText("")
        etPaymentFees.setText("")
        etPaymentReference.setText("")
        etPaymentNotes.setText("")

        tvPaymentFormError.visibility = View.GONE

        selectedPaymentObligationId = id

        refreshPaymentObligationSpinner(id)

        paymentFormCard.visibility = View.VISIBLE

        paymentFormCard.post {
            findViewById<android.widget.ScrollView>(
                R.id.obligationsScroll
            ).smoothScrollTo(0, paymentFormCard.top)
        }
    }

    private fun refreshPaymentObligationSpinner(
        preferredId: String? = selectedPaymentObligationId
    ) {

        paymentOptions = obligations.filter {
            it.remaining > 0.009
        }

        val labels = mutableListOf(
            "Select a bill / payment"
        )

        labels.addAll(
            paymentOptions.map {
                "${it.description} - ${formatMoney(it.remaining)}"
            }
        )

        setSpinnerItems(
            spPaymentObligation,
            labels
        )

        val selectedIndex =
            paymentOptions.indexOfFirst {
                it.id == preferredId
            }

        spPaymentObligation.setSelection(
            if (selectedIndex >= 0) selectedIndex + 1 else 0
        )

        selectedPaymentObligationId =
            paymentOptions.getOrNull(selectedIndex)?.id

        updatePaymentOutstanding()
    }

    private fun updatePaymentOutstanding() {

        val item = obligations.find {
            it.id == selectedPaymentObligationId
        }

        if (item == null) {

            tvPaymentOutstandingBalance.text =
                formatMoney(0.0)

            tvPaymentObligationInfo.text =
                "Select an obligation to view its details."

            return
        }

        tvPaymentOutstandingBalance.text =
            formatMoney(item.remaining)

        tvPaymentObligationInfo.text =
            "${item.payee} • Due ${formatDate(item.dueDate)}"
    }

    // ==========================================
    // SAVE PAYMENT
    // ==========================================

    private fun savePayment() {

        val item = obligations.find {
            it.id == selectedPaymentObligationId
        }

        if (item == null) {
            showPaymentError("Please select a payment schedule.")
            return
        }

        val amount = getAmount(etPaymentAmount)

        val paymentDate = selectedPaymentDate

        val method = spPaymentMethod.selectedItem?.toString()
            ?: ""

        val principalEntered =
            etPaymentPrincipal.text.toString().isNotBlank()

        val interestEntered =
            etPaymentInterest.text.toString().isNotBlank()

        val feesEntered =
            etPaymentFees.text.toString().isNotBlank()

        val hasAllocation =
            principalEntered || interestEntered || feesEntered

        var principal = getAmount(etPaymentPrincipal)
        var interest = getAmount(etPaymentInterest)
        var fees = getAmount(etPaymentFees)

        val reference =
            etPaymentReference.text.toString().trim()

        val notes =
            etPaymentNotes.text.toString().trim()

        if (amount <= 0.0) {
            showPaymentError(
                "Please enter a payment amount greater than zero."
            )
            return
        }

        if (paymentDate == null) {
            showPaymentError("Please select a payment date.")
            return
        }

        if (amount > item.remaining + 0.009) {
            showPaymentError(
                "Payment exceeds the outstanding balance of ${formatMoney(item.remaining)}."
            )
            return
        }

        if (hasAllocation) {

            if (principal < 0 || interest < 0 || fees < 0) {
                showPaymentError(
                    "Payment allocations cannot be negative."
                )
                return
            }

            val allocationTotal =
                principal + interest + fees

            if (kotlin.math.abs(allocationTotal - amount) > 0.01) {
                showPaymentError(
                    "Principal, interest and fees must equal the payment amount."
                )
                return
            }

        } else {

            // Default allocation when no breakdown is supplied.
            principal = amount
            interest = 0.0
            fees = 0.0
        }

        val payment = Payment(
            id = UUID.randomUUID().toString(),
            obligationId = item.id,
            obligationName = item.description,
            amount = amount,
            paymentDate = paymentDate,
            method = method,
            principal = principal,
            interest = interest,
            fees = fees,
            reference = reference,
            notes = notes
        )

        payments.add(payment)

        item.paidAmount = (
                item.paidAmount + amount
                ).coerceAtMost(item.amount)

        val wasFullyPaid = item.remaining <= 0.009

        val nextOccurrence = if (wasFullyPaid) {
            createNextRecurringOccurrence(item)
        } else {
            null
        }

        saveLocalData()
        syncPaymentAndSchedule(payment, item, nextOccurrence)

        paymentFormCard.visibility = View.GONE

        selectedPaymentObligationId = null

        renderAll()

        Toast.makeText(
            this,
            "Payment recorded. Firebase will sync it when connected.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showPaymentError(message: String) {

        tvPaymentFormError.text = message
        tvPaymentFormError.visibility = View.VISIBLE
    }

    // ==========================================
    // RECURRING SCHEDULES
    // ==========================================

    private fun createNextRecurringOccurrence(
        original: Obligation
    ): Obligation? {

        val recurrence = original.repeat.lowercase()

        val nextDate = Calendar.getInstance()
        nextDate.timeInMillis = original.dueDate

        when {
            recurrence.contains("week") -> {
                nextDate.add(Calendar.WEEK_OF_YEAR, 1)
            }

            recurrence.contains("quarter") -> {
                nextDate.add(Calendar.MONTH, 3)
            }

            recurrence.contains("month") -> {
                nextDate.add(Calendar.MONTH, 1)
            }

            recurrence.contains("year") -> {
                nextDate.add(Calendar.YEAR, 1)
            }

            else -> return null
        }

        val nextOccurrence = Obligation(
            id = UUID.randomUUID().toString(),
            type = original.type,
            payee = original.payee,
            description = original.description,
            amount = original.amount,
            paidAmount = 0.0,
            dueDate = nextDate.timeInMillis,
            repeat = original.repeat,
            notes = original.notes
        )

        obligations.add(nextOccurrence)
        return nextOccurrence
    }

    // ==========================================
    // STATUS CALCULATIONS
    // ==========================================

    private fun getStatus(item: Obligation): String {

        if (item.remaining <= 0.009) {
            return "Paid"
        }

        val due = startOfDay(item.dueDate)
        val today = startOfDay(System.currentTimeMillis())

        return when {

            due < today -> "Overdue"

            due == today -> "Due Today"

            item.paidAmount > 0.009 -> "Partially Paid"

            else -> "Upcoming"
        }
    }

    private fun isPartiallyPaid(item: Obligation): Boolean {

        return item.paidAmount > 0.009 &&
                item.remaining > 0.009
    }

    private fun statusColor(status: String): String {

        return when (status) {

            "Paid" -> "#149B71"

            "Overdue" -> "#D85D68"

            "Due Today" -> "#D58A36"

            "Partially Paid" -> "#4285D4"

            else -> "#6558D3"
        }
    }

    // ==========================================
    // SUMMARY STATISTICS
    // ==========================================

    private fun updateStatistics() {

        val unpaid = obligations.sumOf {
            it.remaining
        }

        val paid = obligations.sumOf {
            it.paidAmount
        }

        val today = startOfDay(
            System.currentTimeMillis()
        )

        val calendar = Calendar.getInstance()

        val currentMonth = calendar.get(Calendar.MONTH)
        val currentYear = calendar.get(Calendar.YEAR)

        val dueThisMonth = obligations.filter {

            val due = Calendar.getInstance()
            due.timeInMillis = it.dueDate

            due.get(Calendar.MONTH) == currentMonth &&
                    due.get(Calendar.YEAR) == currentYear &&
                    it.remaining > 0.009

        }.sumOf {
            it.remaining
        }

        val upcomingCount = obligations.count {
            it.remaining > 0.009 &&
                    startOfDay(it.dueDate) > today
        }

        val dueTodayCount = obligations.count {
            it.remaining > 0.009 &&
                    startOfDay(it.dueDate) == today
        }

        val overdueCount = obligations.count {
            it.remaining > 0.009 &&
                    startOfDay(it.dueDate) < today
        }

        val partiallyPaidCount = obligations.count {
            isPartiallyPaid(it)
        }

        val paidCount = obligations.count {
            it.remaining <= 0.009
        }

        findViewById<TextView>(
            R.id.tvTotalUnpaidObligations
        ).text = formatMoney(unpaid)

        findViewById<TextView>(
            R.id.tvTotalObligationsPaid
        ).text = formatMoney(paid)

        findViewById<TextView>(
            R.id.tvObligationsDueThisMonth
        ).text = formatMoney(dueThisMonth)

        findViewById<TextView>(
            R.id.tvUpcomingObligationCount
        ).text = upcomingCount.toString()

        findViewById<TextView>(
            R.id.tvDueTodayCount
        ).text = dueTodayCount.toString()

        findViewById<TextView>(
            R.id.tvOverdueObligationCount
        ).text = overdueCount.toString()

        findViewById<TextView>(
            R.id.tvPartiallyPaidCount
        ).text = partiallyPaidCount.toString()

        findViewById<TextView>(
            R.id.tvPaidObligationCount
        ).text = paidCount.toString()

        findViewById<TextView>(
            R.id.tvObligationRecordCount
        ).text = "${obligations.size} records"
    }

    // ==========================================
    // OBLIGATION LIST
    // ==========================================

    private fun renderObligations() {

        val container = findViewById<LinearLayout>(
            R.id.obligationsListContainer
        )

        val emptyState = findViewById<View>(
            R.id.obligationsEmptyState
        )

        container.removeAllViews()

        val filtered = getFilteredObligations()

        emptyState.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE

        container.visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE

        val summary = findViewById<TextView>(
            R.id.tvObligationFilterSummary
        )

        summary.text = when {

            obligations.isEmpty() ->
                "Add your first bill or scheduled payment."

            filtered.isEmpty() ->
                "No obligations match your current filters."

            else ->
                "Showing ${filtered.size} of ${obligations.size} payment schedules."
        }

        filtered.forEach { item ->

            container.addView(
                createObligationCard(item)
            )
        }
    }

    private fun createObligationCard(
        item: Obligation
    ): View {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )
            background = roundedBackground(
                "#FFFFFF",
                "#E8EAF0"
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = makeText(
            item.description,
            14f,
            "#202334",
            true
        )

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val status = getStatus(item)

        val statusView = makeText(
            status,
            10f,
            statusColor(status),
            true
        ).apply {
            setPadding(
                dp(9),
                dp(6),
                dp(9),
                dp(6)
            )
            background = roundedBackground(
                "#F5F6FA",
                statusColor(status)
            )
        }

        header.addView(statusView)

        card.addView(header)

        card.addView(
            makeText(
                "${item.payee} • ${item.type}",
                11f,
                "#858B9E"
            ).apply {
                setPadding(0, dp(7), 0, dp(7))
            }
        )

        card.addView(
            makeText(
                "Due: ${formatDate(item.dueDate)}",
                11f,
                "#62687A"
            )
        )

        val amountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

        val amountColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        amountColumn.addView(
            makeText(
                "AMOUNT DUE",
                9f,
                "#858B9E",
                true
            )
        )

        amountColumn.addView(
            makeText(
                formatMoney(item.amount),
                15f,
                "#202334",
                true
            ).apply {
                setPadding(0, dp(3), 0, 0)
            }
        )

        amountRow.addView(
            amountColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val remainingColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        remainingColumn.addView(
            makeText(
                "REMAINING",
                9f,
                "#858B9E",
                true
            )
        )

        remainingColumn.addView(
            makeText(
                formatMoney(item.remaining),
                15f,
                if (item.remaining <= 0.009) "#149B71" else "#D85D68",
                true
            ).apply {
                setPadding(0, dp(3), 0, 0)
            }
        )

        amountRow.addView(remainingColumn)

        card.addView(amountRow)

        if (item.paidAmount > 0.009) {
            card.addView(
                makeText(
                    "Already paid: ${formatMoney(item.paidAmount)}",
                    10f,
                    "#149B71"
                ).apply {
                    setPadding(0, 0, 0, dp(10))
                }
            )
        }

        if (item.notes.isNotBlank()) {
            card.addView(
                makeText(
                    item.notes,
                    10f,
                    "#858B9E"
                ).apply {
                    setPadding(0, 0, 0, dp(10))
                }
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (item.remaining > 0.009) {

            actions.addView(
                createAction(
                    "Record Payment",
                    "#6558D3",
                    "#FFFFFF"
                ) {
                    showPaymentForm(item.id)
                },
                buttonLayoutParams()
            )
        }

        actions.addView(
            createAction(
                "Edit",
                "#EFEDFF",
                "#6558D3"
            ) {
                showObligationForm(item.id)
            },
            buttonLayoutParams()
        )

        actions.addView(
            createAction(
                "Delete",
                "#FFF0F0",
                "#D85D68"
            ) {
                confirmDeleteObligation(item.id)
            },
            buttonLayoutParams()
        )

        card.addView(actions)

        return card.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(11)
            }
        }
    }

    // ==========================================
    // DELETE OBLIGATION
    // ==========================================

    private fun confirmDeleteObligation(id: String) {

        val item = obligations.find {
            it.id == id
        } ?: return

        AlertDialog.Builder(this)
            .setTitle("Delete Payment Schedule")
            .setMessage(
                "Delete ${item.description} and its payment history? This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->

                obligations.removeAll {
                    it.id == id
                }

                // Preserve historical payments instead of
                // silently deleting financial transactions.
                saveLocalData()
                obligationsCollection.document(id)
                    .delete()
                    .addOnFailureListener { error ->
                        showFirebaseError(error)
                    }

                renderAll()

                Toast.makeText(
                    this,
                    "Payment schedule deleted. Firebase sync is queued.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    // ==========================================
    // PAYMENT HISTORY
    // ==========================================

    private fun renderPaymentHistory() {

        val container = findViewById<LinearLayout>(
            R.id.paymentHistoryContainer
        )

        val emptyState = findViewById<View>(
            R.id.tvPaymentHistoryEmpty
        )

        container.removeAllViews()

        val history = payments.sortedByDescending {
            it.paymentDate
        }

        emptyState.visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE

        container.visibility =
            if (history.isEmpty()) View.GONE else View.VISIBLE

        history.forEach { payment ->

            container.addView(
                createPaymentHistoryCard(payment)
            )
        }
    }

    private fun createPaymentHistoryCard(
        payment: Payment
    ): View {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )
            background = roundedBackground(
                "#F8F8FC",
                "#EAECF2"
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        details.addView(
            makeText(
                payment.obligationName,
                13f,
                "#202334",
                true
            )
        )

        details.addView(
            makeText(
                "${formatDate(payment.paymentDate)} • ${payment.method}",
                10f,
                "#858B9E"
            ).apply {
                setPadding(0, dp(5), 0, 0)
            }
        )

        row.addView(
            details,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        row.addView(
            makeText(
                formatMoney(payment.amount),
                14f,
                "#149B71",
                true
            )
        )

        card.addView(row)

        if (payment.reference.isNotBlank()) {
            card.addView(
                makeText(
                    "Reference: ${payment.reference}",
                    10f,
                    "#858B9E"
                ).apply {
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

        if (payment.principal > 0 ||
            payment.interest > 0 ||
            payment.fees > 0
        ) {

            card.addView(
                makeText(
                    "Principal: ${formatMoney(payment.principal)}  •  Interest: ${formatMoney(payment.interest)}  •  Fees: ${formatMoney(payment.fees)}",
                    9f,
                    "#62687A"
                ).apply {
                    setPadding(0, dp(7), 0, 0)
                }
            )
        }

        if (payment.notes.isNotBlank()) {
            card.addView(
                makeText(
                    payment.notes,
                    10f,
                    "#858B9E"
                ).apply {
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }

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
    // DYNAMIC UI HELPERS
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
                dp(10),
                dp(9),
                dp(10),
                dp(9)
            )

            background = roundedBackground(
                backgroundColor,
                backgroundColor
            )

            isClickable = true
            isFocusable = true

            setOnClickListener {
                action()
            }
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(7)
        }
    }

    private fun roundedBackground(
        fill: String,
        stroke: String
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(
                Color.parseColor(fill)
            )

            cornerRadius = dp(11).toFloat()

            setStroke(
                dp(1),
                Color.parseColor(stroke)
            )
        }
    }

    private fun dp(value: Int): Int {

        return (
                value * resources.displayMetrics.density
                ).toInt()
    }

    // ==========================================
    // RENDER ALL
    // ==========================================

    private fun renderAll() {

        updateStatistics()
        renderObligations()
        renderPaymentHistory()

        refreshPaymentObligationSpinner(
            selectedPaymentObligationId
        )
    }

    // ==========================================
    // LOCAL STORAGE
    // ==========================================

    private fun saveLocalData() {

        val obligationArray = JSONArray()

        obligations.forEach { item ->

            val json = JSONObject().apply {

                put("id", item.id)
                put("type", item.type)
                put("payee", item.payee)
                put("description", item.description)
                put("amount", item.amount)
                put("paidAmount", item.paidAmount)
                put("dueDate", item.dueDate)
                put("repeat", item.repeat)
                put("notes", item.notes)
            }

            obligationArray.put(json)
        }

        val paymentArray = JSONArray()

        payments.forEach { item ->

            val json = JSONObject().apply {

                put("id", item.id)
                put("obligationId", item.obligationId)
                put("obligationName", item.obligationName)
                put("amount", item.amount)
                put("paymentDate", item.paymentDate)
                put("method", item.method)
                put("principal", item.principal)
                put("interest", item.interest)
                put("fees", item.fees)
                put("reference", item.reference)
                put("notes", item.notes)
            }

            paymentArray.put(json)
        }

        prefs.edit()
            .putString(
                "obligations",
                obligationArray.toString()
            )
            .putString(
                "payments",
                paymentArray.toString()
            )
            .apply()
    }

    private fun loadLocalData() {
        obligations.clear()
        payments.clear()

        val hasUserCache =
            prefs.contains("obligations") || prefs.contains("payments")

        if (hasUserCache) {
            legacyMigrationPending =
                prefs.getBoolean("legacy_migration_pending", false)
            readLocalDataFrom(prefs)
            return
        }

        // One-time migration from the old device-wide cache. The migration
        // owner guard prevents a second account on the same phone from
        // receiving another user's old local records.
        val legacyPrefs = getSharedPreferences(
            "money_tracker_obligations",
            MODE_PRIVATE
        )
        val migrationComplete =
            prefs.getBoolean("legacy_migration_complete", false)
        val migrationOwner =
            legacyPrefs.getString("migrationOwnerUid", null)

        val mayMigrateLegacy =
            !migrationComplete &&
                    (migrationOwner.isNullOrBlank() || migrationOwner == userId)

        if (mayMigrateLegacy) {
            readLocalDataFrom(legacyPrefs)

            if (obligations.isNotEmpty() || payments.isNotEmpty()) {
                legacyMigrationPending = true
                legacyPrefs.edit()
                    .putString("migrationOwnerUid", userId)
                    .apply()
                prefs.edit()
                    .putBoolean("legacy_migration_pending", true)
                    .apply()
                saveLocalData()
            }
        }
    }

    private fun readLocalDataFrom(source: SharedPreferences) {
        try {
            val obligationJson = JSONArray(
                source.getString("obligations", "[]") ?: "[]"
            )

            for (i in 0 until obligationJson.length()) {
                val json = obligationJson.getJSONObject(i)
                obligations.add(
                    Obligation(
                        id = json.optString("id"),
                        type = json.optString("type"),
                        payee = json.optString("payee"),
                        description = json.optString("description"),
                        amount = json.optDouble("amount", 0.0),
                        paidAmount = json.optDouble("paidAmount", 0.0),
                        dueDate = json.optLong("dueDate", 0L),
                        repeat = json.optString("repeat", "One-time"),
                        notes = json.optString("notes", "")
                    )
                )
            }

            val paymentJson = JSONArray(
                source.getString("payments", "[]") ?: "[]"
            )

            for (i in 0 until paymentJson.length()) {
                val json = paymentJson.getJSONObject(i)
                payments.add(
                    Payment(
                        id = json.optString("id"),
                        obligationId = json.optString("obligationId"),
                        obligationName = json.optString("obligationName"),
                        amount = json.optDouble("amount", 0.0),
                        paymentDate = json.optLong("paymentDate", 0L),
                        method = json.optString("method"),
                        principal = json.optDouble("principal", 0.0),
                        interest = json.optDouble("interest", 0.0),
                        fees = json.optDouble("fees", 0.0),
                        reference = json.optString("reference"),
                        notes = json.optString("notes")
                    )
                )
            }
        } catch (exception: Exception) {
            findViewById<TextView>(R.id.tvObligationsError)?.apply {
                text = "Unable to read saved payment schedules: ${exception.message}"
                visibility = View.VISIBLE
            }
        }
    }

    // ==========================================
    // FIREBASE / OFFLINE SYNCHRONIZATION
    // ==========================================

    private fun startFirestoreListeners() {
        findViewById<View>(R.id.obligationsLoading).visibility =
            if (obligations.isEmpty()) View.VISIBLE else View.GONE

        findViewById<View>(R.id.paymentHistoryLoading).visibility =
            if (payments.isEmpty()) View.VISIBLE else View.GONE

        findViewById<View>(R.id.obligationsProgress).visibility =
            if (obligations.isEmpty() || payments.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }

        obligationsListener = obligationsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                obligationsLoaded = true
                updateLoadingState()
                showFirebaseError(error)
                return@addSnapshotListener
            }

            if (snapshot == null) return@addSnapshotListener

            val remoteItems = snapshot.documents.mapNotNull {
                documentToObligation(it)
            }
            val localItems = obligations.toList()
            val remoteIds = remoteItems.map { it.id }.toSet()

            val preserveEmptyOfflineSnapshot =
                snapshot.metadata.isFromCache &&
                        remoteItems.isEmpty() &&
                        localItems.isNotEmpty()

            if (legacyMigrationPending) {
                val localOnly = localItems.filter { it.id !in remoteIds }

                if (!snapshot.metadata.isFromCache && localOnly.isNotEmpty()) {
                    uploadMissingObligations(localOnly)
                }

                obligations.clear()
                obligations.addAll(remoteItems)
                obligations.addAll(localOnly)
            } else if (!preserveEmptyOfflineSnapshot) {
                obligations.clear()
                obligations.addAll(remoteItems)
            }

            obligationsLoaded = true
            saveLocalData()
            updateLoadingState()
            renderAll()

            if (!snapshot.metadata.isFromCache) {
                remoteObligationsServerLoaded = true
                maybeFinishLegacyMigration()
                clearFirebaseErrorWhenReady()
            }
        }

        paymentsListener = paymentsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                paymentsLoaded = true
                updateLoadingState()
                showFirebaseError(error)
                return@addSnapshotListener
            }

            if (snapshot == null) return@addSnapshotListener

            // This collection is shared with other finance modules. Only
            // display transactions created by this Bills & Payments screen.
            val remoteItems = snapshot.documents.mapNotNull {
                documentToPayment(it)
            }.sortedByDescending { it.paymentDate }

            val localItems = payments.toList()
            val remoteIds = remoteItems.map { it.id }.toSet()

            val preserveEmptyOfflineSnapshot =
                snapshot.metadata.isFromCache &&
                        remoteItems.isEmpty() &&
                        localItems.isNotEmpty()

            if (legacyMigrationPending) {
                val localOnly = localItems.filter { it.id !in remoteIds }

                if (!snapshot.metadata.isFromCache && localOnly.isNotEmpty()) {
                    uploadMissingPayments(localOnly)
                }

                payments.clear()
                payments.addAll(remoteItems)
                payments.addAll(localOnly)
            } else if (!preserveEmptyOfflineSnapshot) {
                payments.clear()
                payments.addAll(remoteItems)
            }

            paymentsLoaded = true
            saveLocalData()
            updateLoadingState()
            renderAll()

            if (!snapshot.metadata.isFromCache) {
                remotePaymentsServerLoaded = true
                maybeFinishLegacyMigration()
                clearFirebaseErrorWhenReady()
            }
        }
    }

    private fun updateLoadingState() {
        if (obligationsLoaded) {
            findViewById<View>(R.id.obligationsLoading).visibility = View.GONE
        }
        if (paymentsLoaded) {
            findViewById<View>(R.id.paymentHistoryLoading).visibility = View.GONE
        }
        if (obligationsLoaded && paymentsLoaded) {
            findViewById<View>(R.id.obligationsProgress).visibility = View.GONE
        }
    }

    private fun maybeFinishLegacyMigration() {
        if (
            legacyMigrationPending &&
            remoteObligationsServerLoaded &&
            remotePaymentsServerLoaded
        ) {
            legacyMigrationPending = false
            prefs.edit()
                .putBoolean("legacy_migration_pending", false)
                .putBoolean("legacy_migration_complete", true)
                .apply()
        }
    }

    private fun clearFirebaseErrorWhenReady() {
        if (remoteObligationsServerLoaded && remotePaymentsServerLoaded) {
            findViewById<View>(R.id.tvObligationsError).visibility = View.GONE
        }
    }

    private fun showFirebaseError(error: Exception) {
        findViewById<TextView>(R.id.tvObligationsError)?.apply {
            text = "Firebase sync issue: ${error.localizedMessage ?: "Please check your connection or access permissions."} Your saved local records remain available."
            visibility = View.VISIBLE
        }
    }

    private fun uploadMissingObligations(items: List<Obligation>) {
        if (items.isEmpty()) return

        val batch = firestore.batch()
        items.forEach { item ->
            batch.set(
                obligationsCollection.document(item.id),
                obligationToMap(item)
            )
        }
        batch.commit().addOnFailureListener { error ->
            showFirebaseError(error)
        }
    }

    private fun uploadMissingPayments(items: List<Payment>) {
        if (items.isEmpty()) return

        val batch = firestore.batch()
        items.forEach { item ->
            batch.set(
                paymentsCollection.document(item.id),
                paymentToMap(item)
            )
        }
        batch.commit().addOnFailureListener { error ->
            showFirebaseError(error)
        }
    }

    private fun syncObligation(item: Obligation) {
        obligationsCollection.document(item.id)
            .set(obligationToMap(item))
            .addOnFailureListener { error ->
                showFirebaseError(error)
            }
    }

    private fun syncPaymentAndSchedule(
        payment: Payment,
        updatedObligation: Obligation,
        nextOccurrence: Obligation?
    ) {
        val batch = firestore.batch()

        batch.set(
            obligationsCollection.document(updatedObligation.id),
            obligationToMap(updatedObligation)
        )
        batch.set(
            paymentsCollection.document(payment.id),
            paymentToMap(payment)
        )

        nextOccurrence?.let { next ->
            batch.set(
                obligationsCollection.document(next.id),
                obligationToMap(next)
            )
        }

        batch.commit().addOnFailureListener { error ->
            showFirebaseError(error)
        }
    }

    private fun obligationToMap(item: Obligation): Map<String, Any> {
        return mapOf(
            "id" to item.id,
            "type" to item.type,
            "payee" to item.payee,
            "description" to item.description,
            "amount" to item.amount,
            "paidAmount" to item.paidAmount,
            "dueDate" to item.dueDate,
            "repeat" to item.repeat,
            "notes" to item.notes
        )
    }

    private fun paymentToMap(item: Payment): Map<String, Any> {
        return mapOf(
            "id" to item.id,
            "obligationId" to item.obligationId,
            "obligationName" to item.obligationName,
            "amount" to item.amount,
            "paymentDate" to item.paymentDate,
            "paymentDateMillis" to item.paymentDate,
            "dateMillis" to item.paymentDate,
            "date" to Date(item.paymentDate),
            "dateString" to SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Date(item.paymentDate)),
            "method" to item.method,
            "paymentMethod" to item.method,
            "principal" to item.principal,
            "interest" to item.interest,
            "fees" to item.fees,
            "reference" to item.reference,
            "notes" to item.notes,
            "source" to "obligation",
            "sourceType" to "obligation",
            "paymentType" to "obligation",
            "type" to "obligation_payment",
            "category" to "Bills & Payments",
            "description" to "Bill payment: ${item.obligationName}",
            "title" to "Bill Payment",
            "obligationName" to item.obligationName,
            "status" to "paid"
        )
    }

    private fun documentToObligation(document: DocumentSnapshot): Obligation? {
        val data = document.data ?: return null

        return Obligation(
            id = document.id,
            type = data["type"] as? String ?: "",
            payee = data["payee"] as? String ?: "",
            description = data["description"] as? String ?: "",
            amount = valueAsDouble(data["amount"]),
            paidAmount = valueAsDouble(data["paidAmount"]),
            dueDate = valueAsLong(data["dueDate"]),
            repeat = data["repeat"] as? String ?: "One-time",
            notes = data["notes"] as? String ?: ""
        )
    }

    private fun documentToPayment(document: DocumentSnapshot): Payment? {
        val data = document.data ?: return null
        val obligationId = data["obligationId"] as? String ?: ""
        val source = (data["sourceType"] as? String)
            ?: (data["source"] as? String)
            ?: (data["paymentType"] as? String)
            ?: (data["type"] as? String)
            ?: ""

        val isObligationPayment =
            source.contains("obligation", ignoreCase = true) ||
                    source.contains("bill", ignoreCase = true) ||
                    obligationId.isNotBlank()

        if (!isObligationPayment) {
            return null
        }

        return Payment(
            id = document.id,
            obligationId = obligationId,
            obligationName = (data["obligationName"] as? String)
                ?: (data["description"] as? String)
                ?: "Bill payment",
            amount = valueAsDouble(data["amount"]),
            paymentDate = valueAsLong(
                data["dateMillis"] ?: data["paymentDateMillis"] ?:
                data["paymentDate"] ?: data["date"] ?: data["createdAt"]
            ),
            method = (data["method"] as? String)
                ?: (data["paymentMethod"] as? String)
                ?: "",
            principal = valueAsDouble(data["principal"]),
            interest = valueAsDouble(data["interest"]),
            fees = valueAsDouble(data["fees"]),
            reference = data["reference"] as? String ?: "",
            notes = data["notes"] as? String ?: ""
        )
    }

    private fun valueAsDouble(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(",", "").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun valueAsLong(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is Timestamp -> value.toDate().time
            is Date -> value.time
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    // ==========================================
    // GENERAL HELPERS
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

    private fun formatDate(date: Long): String {

        return dateFormatter.format(date)
    }

    private fun startOfDay(time: Long): Long {

        val calendar = Calendar.getInstance()

        calendar.timeInMillis = time

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }

    private fun setSpinnerSelection(
        spinner: Spinner,
        value: String
    ) {

        for (index in 0 until spinner.count) {

            if (
                spinner.getItemAtPosition(index).toString()
                    .equals(value, ignoreCase = true)
            ) {

                spinner.setSelection(index)
                return
            }
        }
    }
}
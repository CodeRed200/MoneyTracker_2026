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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.google.firebase.firestore.SetOptions
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Loans & Interest
 *
 * Firestore paths:
 *   users/{uid}/loans
 *   users/{uid}/payments
 *
 * Loan documents store the generated installment schedule in an "installments"
 * array. Repayments are written to the user's existing "payments" collection
 * with source="loan" and the related loanId. This makes loan repayments visible
 * to other finance screens that already read the user's payments collection.
 *
 * A user-scoped SharedPreferences cache is maintained for immediate offline
 * display. Firestore's Android offline persistence also queues writes while the
 * device is offline.
 */
class LoansActivity : AppCompatActivity() {

    private data class LoanInstallment(
        val number: Int,
        val dueDateMillis: Long,
        val amount: Double,
        val paidAmount: Double = 0.0,
        val status: String = "UNPAID",
        val paidAtMillis: Long = 0L
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "number" to number,
            "dueDateMillis" to dueDateMillis,
            "amount" to amount,
            "paidAmount" to paidAmount,
            "status" to status,
            "paidAtMillis" to paidAtMillis
        )
    }

    private data class LoanRecord(
        val id: String,
        val provider: String,
        val name: String,
        val principal: Double,
        val totalInterest: Double,
        val fees: Double,
        val totalAmount: Double,
        val totalPaid: Double,
        val remainingBalance: Double,
        val termMonths: Int,
        val firstDueDateMillis: Long,
        val interestRate: Double,
        val ratePeriod: String,
        val interestMethod: String,
        val monthlyInstallment: Double,
        val notes: String,
        val installments: List<LoanInstallment>
    )

    private data class LoanPayment(
        val id: String,
        val loanId: String,
        val provider: String,
        val loanName: String,
        val amount: Double,
        val dateMillis: Long,
        val notes: String
    )

    private lateinit var loanFormCard: View
    private lateinit var loansScroll: ScrollView
    private lateinit var loanListContainer: LinearLayout
    private lateinit var loanPaymentHistoryContainer: LinearLayout
    private lateinit var upcomingInstallmentContainer: LinearLayout
    private lateinit var loansLoading: View
    private lateinit var loansEmptyState: View
    private lateinit var tvUpcomingLoanEmpty: TextView
    private lateinit var tvLoanPaymentHistoryEmpty: TextView
    private lateinit var tvLoansError: TextView
    private lateinit var loansProgress: ProgressBar

    private lateinit var etLoanId: EditText
    private lateinit var etLoanProvider: EditText
    private lateinit var etLoanName: EditText
    private lateinit var etLoanPrincipal: EditText
    private lateinit var etLoanTerm: EditText
    private lateinit var etLoanFixedInterest: EditText
    private lateinit var etLoanFees: EditText
    private lateinit var etLoanRate: EditText
    private lateinit var etLoanNotes: EditText
    private lateinit var etLoanSearch: EditText

    private lateinit var tvLoanFirstDueDate: TextView
    private lateinit var tvLoanPreviewInterest: TextView
    private lateinit var tvLoanPreviewTotal: TextView
    private lateinit var tvLoanPreviewInstallment: TextView
    private lateinit var tvLoanFormError: TextView
    private lateinit var tvLoanMethodDescription: TextView
    private lateinit var tvLoanFormTitle: TextView
    private lateinit var tvLoanEditNotice: TextView
    private lateinit var tvLoanRecordCount: TextView

    private lateinit var spLoanInterestMethod: Spinner
    private lateinit var spLoanRatePeriod: Spinner
    private lateinit var spLoanStatusFilter: Spinner

    private lateinit var tvTotalOutstandingDebt: TextView
    private lateinit var tvOutstandingDebtDescription: TextView
    private lateinit var progressOverallLoanRepayment: ProgressBar
    private lateinit var tvOverallLoanProgress: TextView
    private lateinit var tvTotalLoanPrincipal: TextView
    private lateinit var tvTotalLoanRepaid: TextView
    private lateinit var tvTotalLoanInterest: TextView
    private lateinit var tvActiveLoanCount: TextView
    private lateinit var tvUpcomingLoanCount: TextView
    private lateinit var tvUpcomingInstallmentTotal: TextView

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var loansListener: ListenerRegistration? = null
    private var paymentsListener: ListenerRegistration? = null

    private var userId: String = ""
    private var firstDueDate: Calendar? = null
    private var loans: List<LoanRecord> = emptyList()
    private var loanPayments: List<LoanPayment> = emptyList()
    private var loansLoaded = false
    private var paymentsLoaded = false

    private val currencyFormatter: NumberFormat =
        NumberFormat.getCurrencyInstance(Locale("en", "PH"))

    private val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val cachePreferences by lazy {
        getSharedPreferences("loans_cache_$userId", Context.MODE_PRIVATE)
    }

    private val loansCollection
        get() = firestore.collection("users").document(userId).collection("loans")

    private val paymentsCollection
        get() = firestore.collection("users").document(userId).collection("payments")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_loans)

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
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
            return
        }

        userId = currentUser.uid

        initializeViews()
        setupWindowInsets()
        setupSpinners()
        setupClickListeners()
        setupTextWatchers()
        updateInterestDescription()
        updateLoanPreview()

        loadLocalCache()
        startFirestoreListeners()
    }

    private fun openLogin() {
        startActivity(
            Intent(this, Login::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun initializeViews() {
        loanFormCard = findViewById(R.id.loanFormCard)
        loansScroll = findViewById(R.id.loansScroll)
        loanListContainer = findViewById(R.id.loanListContainer)
        loanPaymentHistoryContainer = findViewById(R.id.loanPaymentHistoryContainer)
        upcomingInstallmentContainer = findViewById(R.id.upcomingInstallmentContainer)
        loansLoading = findViewById(R.id.loansLoading)
        loansEmptyState = findViewById(R.id.loansEmptyState)
        tvUpcomingLoanEmpty = findViewById(R.id.tvUpcomingLoanEmpty)
        tvLoanPaymentHistoryEmpty = findViewById(R.id.tvLoanPaymentHistoryEmpty)
        tvLoansError = findViewById(R.id.tvLoansError)
        loansProgress = findViewById(R.id.loansProgress)

        etLoanId = findViewById(R.id.etLoanId)
        etLoanProvider = findViewById(R.id.etLoanProvider)
        etLoanName = findViewById(R.id.etLoanName)
        etLoanPrincipal = findViewById(R.id.etLoanPrincipal)
        etLoanTerm = findViewById(R.id.etLoanTerm)
        etLoanFixedInterest = findViewById(R.id.etLoanFixedInterest)
        etLoanFees = findViewById(R.id.etLoanFees)
        etLoanRate = findViewById(R.id.etLoanRate)
        etLoanNotes = findViewById(R.id.etLoanNotes)
        etLoanSearch = findViewById(R.id.etLoanSearch)

        tvLoanFirstDueDate = findViewById(R.id.tvLoanFirstDueDate)
        tvLoanPreviewInterest = findViewById(R.id.tvLoanPreviewInterest)
        tvLoanPreviewTotal = findViewById(R.id.tvLoanPreviewTotal)
        tvLoanPreviewInstallment = findViewById(R.id.tvLoanPreviewInstallment)
        tvLoanFormError = findViewById(R.id.tvLoanFormError)
        tvLoanMethodDescription = findViewById(R.id.tvLoanMethodDescription)
        tvLoanFormTitle = findViewById(R.id.tvLoanFormTitle)
        tvLoanEditNotice = findViewById(R.id.tvLoanEditNotice)
        tvLoanRecordCount = findViewById(R.id.tvLoanRecordCount)

        spLoanInterestMethod = findViewById(R.id.spLoanInterestMethod)
        spLoanRatePeriod = findViewById(R.id.spLoanRatePeriod)
        spLoanStatusFilter = findViewById(R.id.spLoanStatusFilter)

        tvTotalOutstandingDebt = findViewById(R.id.tvTotalOutstandingDebt)
        tvOutstandingDebtDescription = findViewById(R.id.tvOutstandingDebtDescription)
        progressOverallLoanRepayment = findViewById(R.id.progressOverallLoanRepayment)
        tvOverallLoanProgress = findViewById(R.id.tvOverallLoanProgress)
        tvTotalLoanPrincipal = findViewById(R.id.tvTotalLoanPrincipal)
        tvTotalLoanRepaid = findViewById(R.id.tvTotalLoanRepaid)
        tvTotalLoanInterest = findViewById(R.id.tvTotalLoanInterest)
        tvActiveLoanCount = findViewById(R.id.tvActiveLoanCount)
        tvUpcomingLoanCount = findViewById(R.id.tvUpcomingLoanCount)
        tvUpcomingInstallmentTotal = findViewById(R.id.tvUpcomingInstallmentTotal)
    }

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

    private fun setupSpinners() {
        setupSpinner(
            spLoanInterestMethod,
            listOf(
                "Fixed total interest (from lender)",
                "Simple interest",
                "Flat rate",
                "Reducing balance",
                "Compound interest"
            )
        )

        setupSpinner(spLoanRatePeriod, listOf("Per month", "Per year"))
        setupSpinner(spLoanStatusFilter, listOf("All Loans", "Active", "Paid Off", "Overdue"))

        spLoanInterestMethod.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateInterestDescription()
                    updateLoanPreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        spLoanRatePeriod.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateLoanPreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        spLoanStatusFilter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    renderLoanList()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setupSpinner(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnLoansBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnAddLoan).setOnClickListener {
            resetLoanForm()
            showLoanForm()
        }

        findViewById<View>(R.id.btnCloseLoanForm).setOnClickListener {
            loanFormCard.visibility = View.GONE
            hideKeyboard()
        }

        tvLoanFirstDueDate.setOnClickListener { showDatePicker() }

        findViewById<View>(R.id.chipShopeePay).setOnClickListener {
            selectLender("ShopeePay")
        }
        findViewById<View>(R.id.chipHomeCredit).setOnClickListener {
            selectLender("Home Credit")
        }
        findViewById<View>(R.id.chipGLoan).setOnClickListener {
            selectLender("GLoan")
        }
        findViewById<View>(R.id.chipOtherLender).setOnClickListener {
            etLoanProvider.setText("")
            etLoanProvider.requestFocus()
        }

        findViewById<View>(R.id.btnSaveLoan).setOnClickListener {
            validateAndSaveLoan()
        }

        findViewById<View>(R.id.btnViewAllLoanPayments).setOnClickListener {
            showFullRepaymentSchedule()
        }

        findViewById<View>(R.id.loansToolbar).setOnLongClickListener {
            refreshFromFirestore()
            true
        }
    }

    private fun setupTextWatchers() {
        val calculatorFields = listOf(
            etLoanPrincipal,
            etLoanTerm,
            etLoanFixedInterest,
            etLoanFees,
            etLoanRate
        )

        calculatorFields.forEach { editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateLoanPreview()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        etLoanSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                renderLoanList()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun showLoanForm() {
        loanFormCard.visibility = View.VISIBLE
        tvLoanFormError.visibility = View.GONE
        loanFormCard.post {
            loansScroll.smoothScrollTo(0, loanFormCard.top)
        }
    }

    private fun selectLender(provider: String) {
        etLoanProvider.setText(provider)
        etLoanName.requestFocus()
        Toast.makeText(this, "$provider selected.", Toast.LENGTH_SHORT).show()
    }

    private fun resetLoanForm() {
        setFinancialFieldsEnabled(true)
        etLoanId.setText("")
        etLoanProvider.setText("")
        etLoanName.setText("")
        etLoanPrincipal.setText("")
        etLoanTerm.setText("12")
        etLoanFixedInterest.setText("0")
        etLoanFees.setText("0")
        etLoanRate.setText("0")
        etLoanNotes.setText("")
        firstDueDate = null
        tvLoanFirstDueDate.text = "Select first due date"
        tvLoanFormTitle.text = "Add Loan / Installment Plan"
        tvLoanEditNotice.visibility = View.GONE
        tvLoanFormError.visibility = View.GONE
        findViewById<Button>(R.id.btnSaveLoan).text = "Save Loan & Schedule"
        spLoanInterestMethod.setSelection(0)
        spLoanRatePeriod.setSelection(0)
        updateInterestDescription()
        updateLoanPreview()
    }

    private fun showDatePicker() {
        val calendar = firstDueDate ?: Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val selectedDate = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                firstDueDate = selectedDate
                tvLoanFirstDueDate.text = formatDate(selectedDate.timeInMillis)
                tvLoanFormError.visibility = View.GONE
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateInterestDescription() {
        val method = spLoanInterestMethod.selectedItem?.toString() ?: return
        tvLoanMethodDescription.text = when (method) {
            "Fixed total interest (from lender)" ->
                "Uses the total interest amount entered from your lender's official statement."
            "Simple interest" ->
                "Calculates interest using the original principal and the selected monthly or annual rate."
            "Flat rate" ->
                "Calculates interest on the original principal throughout the loan term."
            "Reducing balance" ->
                "Estimates equal monthly installments using an amortized reducing-balance calculation."
            "Compound interest" ->
                "Calculates estimated interest using monthly compounding."
            else -> "Choose an interest method to calculate an estimate."
        }
    }

    private fun updateLoanPreview() {
        if (!::etLoanPrincipal.isInitialized) return

        val principal = getAmount(etLoanPrincipal)
        val term = etLoanTerm.text.toString().toIntOrNull() ?: 0
        val fixedInterest = getAmount(etLoanFixedInterest)
        val fees = getAmount(etLoanFees)
        val rate = getAmount(etLoanRate)

        if (principal <= 0.0 || term <= 0) {
            tvLoanPreviewInterest.text = formatMoney(0.0)
            tvLoanPreviewTotal.text = formatMoney(0.0)
            tvLoanPreviewInstallment.text = formatMoney(0.0)
            return
        }

        val method = spLoanInterestMethod.selectedItem?.toString()
            ?: "Fixed total interest (from lender)"
        val isAnnual = spLoanRatePeriod.selectedItem?.toString() == "Per year"
        val monthlyRate = if (isAnnual) rate / 100.0 / 12.0 else rate / 100.0

        val estimatedInterest = calculateInterest(
            principal = principal,
            months = term,
            monthlyRate = monthlyRate,
            fixedInterest = fixedInterest,
            method = method
        ).coerceAtLeast(0.0)

        val totalAmount = principal + estimatedInterest + fees
        val monthlyInstallment = totalAmount / term

        tvLoanPreviewInterest.text = formatMoney(estimatedInterest)
        tvLoanPreviewTotal.text = formatMoney(totalAmount)
        tvLoanPreviewInstallment.text = formatMoney(monthlyInstallment)
    }

    private fun calculateInterest(
        principal: Double,
        months: Int,
        monthlyRate: Double,
        fixedInterest: Double,
        method: String
    ): Double {
        if (months <= 0 || principal <= 0.0) return 0.0

        return when (method) {
            "Fixed total interest (from lender)" -> fixedInterest
            "Simple interest", "Flat rate" -> principal * monthlyRate * months
            "Reducing balance" -> {
                if (monthlyRate <= 0.0) {
                    0.0
                } else {
                    val denominator = 1.0 - (1.0 + monthlyRate).pow(-months)
                    if (denominator <= 0.0) 0.0
                    else {
                        val installment = principal * monthlyRate / denominator
                        (installment * months) - principal
                    }
                }
            }
            "Compound interest" ->
                principal * ((1.0 + monthlyRate).pow(months) - 1.0)
            else -> fixedInterest
        }
    }

    private fun validateAndSaveLoan() {
        val provider = etLoanProvider.text.toString().trim()
        val loanName = etLoanName.text.toString().trim()
        val principal = getAmount(etLoanPrincipal)
        val term = etLoanTerm.text.toString().toIntOrNull() ?: 0
        val fixedInterest = getAmount(etLoanFixedInterest)
        val fees = getAmount(etLoanFees)
        val rate = getAmount(etLoanRate)

        val errorMessage = when {
            provider.isBlank() -> "Please enter the lender or provider."
            loanName.isBlank() -> "Please enter a loan name."
            principal <= 0.0 -> "Please enter a valid principal amount."
            term !in 1..360 -> "Enter a loan term from 1 to 360 monthly payments."
            fixedInterest < 0.0 || fees < 0.0 || rate < 0.0 ->
                "Interest amounts, rates and fees cannot be negative."
            firstDueDate == null -> "Please select the first installment due date."
            else -> null
        }

        if (errorMessage != null) {
            tvLoanFormError.text = errorMessage
            tvLoanFormError.visibility = View.VISIBLE
            return
        }

        val method = spLoanInterestMethod.selectedItem?.toString()
            ?: "Fixed total interest (from lender)"
        val ratePeriod = spLoanRatePeriod.selectedItem?.toString() ?: "Per month"
        val monthlyRate = if (ratePeriod == "Per year") {
            rate / 100.0 / 12.0
        } else {
            rate / 100.0
        }

        val interest = calculateInterest(
            principal,
            term,
            monthlyRate,
            fixedInterest,
            method
        )

        if (!interest.isFinite() || interest < 0.0) {
            tvLoanFormError.text = "The interest calculation is invalid. Check the rate and term."
            tvLoanFormError.visibility = View.VISIBLE
            return
        }

        tvLoanFormError.visibility = View.GONE
        hideKeyboard()

        val existingId = etLoanId.text.toString().trim()
        val existing = loans.firstOrNull { it.id == existingId }
        val now = System.currentTimeMillis()

        // Once repayments exist, changing the financial terms would rewrite history.
        // In that case, only the lender/name/notes can be edited.
        if (existing != null && existing.totalPaid > 0.009) {
            val updated = existing.copy(
                provider = provider,
                name = loanName,
                notes = etLoanNotes.text.toString().trim()
            )
            loans = loans.map { if (it.id == updated.id) updated else it }
            saveLocalCache()
            renderAll()
            loansCollection.document(updated.id)
                .set(
                    mapOf(
                        "provider" to updated.provider,
                        "name" to updated.name,
                        "notes" to updated.notes,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .addOnFailureListener { showFirebaseError(it) }

            loanFormCard.visibility = View.GONE
            Toast.makeText(
                this,
                "Loan details updated. Financial terms were kept unchanged because repayments already exist.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val dueDateMillis = firstDueDate!!.timeInMillis
        val totalAmount = principal + interest + fees
        val schedule = buildInstallmentSchedule(totalAmount, term, dueDateMillis)
        val loanId = if (existingId.isNotBlank()) existingId else loansCollection.document().id

        val loan = LoanRecord(
            id = loanId,
            provider = provider,
            name = loanName,
            principal = principal,
            totalInterest = interest,
            fees = fees,
            totalAmount = totalAmount,
            totalPaid = existing?.totalPaid ?: 0.0,
            remainingBalance = (totalAmount - (existing?.totalPaid ?: 0.0)).coerceAtLeast(0.0),
            termMonths = term,
            firstDueDateMillis = dueDateMillis,
            interestRate = rate,
            ratePeriod = ratePeriod,
            interestMethod = method,
            monthlyInstallment = totalAmount / term,
            notes = etLoanNotes.text.toString().trim(),
            installments = schedule
        )

        loans = if (existing == null) {
            loans + loan
        } else {
            loans.map { if (it.id == loan.id) loan else it }
        }

        saveLocalCache()
        renderAll()
        loanFormCard.visibility = View.GONE
        resetLoanForm()

        val saveMap = loanToFirestoreMap(loan, includeCreatedAt = existing == null)
        loansCollection.document(loan.id)
            .set(saveMap, SetOptions.merge())
            .addOnFailureListener { showFirebaseError(it) }

        Toast.makeText(
            this,
            "Loan saved on this device. Firebase will sync when a connection is available.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun buildInstallmentSchedule(
        totalAmount: Double,
        termMonths: Int,
        firstDateMillis: Long
    ): List<LoanInstallment> {
        if (termMonths <= 0) return emptyList()

        val totalCents = (totalAmount * 100.0).roundToLong().coerceAtLeast(0L)
        val baseCents = totalCents / termMonths
        val remainderCents = totalCents % termMonths

        val anchor = Calendar.getInstance().apply { timeInMillis = firstDateMillis }
        val preferredDay = anchor.get(Calendar.DAY_OF_MONTH)

        return (0 until termMonths).map { index ->
            val dueDate = (anchor.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, index)
                set(
                    Calendar.DAY_OF_MONTH,
                    min(preferredDay, getActualMaximum(Calendar.DAY_OF_MONTH))
                )
            }
            val amountCents = baseCents + if (index == termMonths - 1) remainderCents else 0L
            LoanInstallment(
                number = index + 1,
                dueDateMillis = dueDate.timeInMillis,
                amount = amountCents / 100.0
            )
        }
    }

    private fun loanToFirestoreMap(
        loan: LoanRecord,
        includeCreatedAt: Boolean
    ): HashMap<String, Any> {
        return hashMapOf<String, Any>().apply {
            put("provider", loan.provider)
            put("name", loan.name)
            put("principal", loan.principal)
            put("totalInterest", loan.totalInterest)
            put("fees", loan.fees)
            put("totalAmount", loan.totalAmount)
            put("totalPaid", loan.totalPaid)
            put("remainingBalance", loan.remainingBalance)
            put("termMonths", loan.termMonths)
            put("firstDueDateMillis", loan.firstDueDateMillis)
            put("interestRate", loan.interestRate)
            put("ratePeriod", loan.ratePeriod)
            put("interestMethod", loan.interestMethod)
            put("monthlyInstallment", loan.monthlyInstallment)
            put("notes", loan.notes)
            put("installments", loan.installments.map { it.toMap() })
            put("status", if (loan.remainingBalance <= 0.009) "PAID_OFF" else "ACTIVE")
            put("updatedAt", FieldValue.serverTimestamp())
            if (includeCreatedAt) put("createdAt", FieldValue.serverTimestamp())
        }
    }

    private fun showEditLoanForm(loan: LoanRecord) {
        etLoanId.setText(loan.id)
        etLoanProvider.setText(loan.provider)
        etLoanName.setText(loan.name)
        etLoanPrincipal.setText(loan.principal.toString())
        etLoanTerm.setText(loan.termMonths.toString())
        etLoanFixedInterest.setText(loan.totalInterest.toString())
        etLoanFees.setText(loan.fees.toString())
        etLoanRate.setText(loan.interestRate.toString())
        etLoanNotes.setText(loan.notes)

        val due = Calendar.getInstance().apply {
            timeInMillis = loan.firstDueDateMillis
        }
        firstDueDate = due
        tvLoanFirstDueDate.text = formatDate(loan.firstDueDateMillis)

        val methodIndex = (0 until spLoanInterestMethod.count).firstOrNull {
            spLoanInterestMethod.getItemAtPosition(it).toString() == loan.interestMethod
        } ?: 0
        spLoanInterestMethod.setSelection(methodIndex)
        spLoanRatePeriod.setSelection(if (loan.ratePeriod == "Per year") 1 else 0)

        tvLoanFormTitle.text = "Edit Loan"
        findViewById<Button>(R.id.btnSaveLoan).text = "Update Loan & Schedule"

        if (loan.totalPaid > 0.009) {
            tvLoanEditNotice.text =
                "Repayments have already been recorded. You can update the lender, loan name and notes; the original amount, interest and schedule will remain unchanged."
            tvLoanEditNotice.visibility = View.VISIBLE
            etLoanPrincipal.isEnabled = false
            etLoanTerm.isEnabled = false
            etLoanFixedInterest.isEnabled = false
            etLoanFees.isEnabled = false
            etLoanRate.isEnabled = false
            spLoanInterestMethod.isEnabled = false
            spLoanRatePeriod.isEnabled = false
            tvLoanFirstDueDate.isEnabled = false
        } else {
            tvLoanEditNotice.visibility = View.GONE
            setFinancialFieldsEnabled(true)
        }

        updateInterestDescription()
        updateLoanPreview()
        showLoanForm()
    }

    private fun setFinancialFieldsEnabled(enabled: Boolean) {
        etLoanPrincipal.isEnabled = enabled
        etLoanTerm.isEnabled = enabled
        etLoanFixedInterest.isEnabled = enabled
        etLoanFees.isEnabled = enabled
        etLoanRate.isEnabled = enabled
        spLoanInterestMethod.isEnabled = enabled
        spLoanRatePeriod.isEnabled = enabled
        tvLoanFirstDueDate.isEnabled = enabled
    }

    private fun showDeleteLoanConfirmation(loan: LoanRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete loan?")
            .setMessage(
                "Delete ${loan.provider} – ${loan.name}? Its repayment transaction records will remain in your payment history."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                loans = loans.filterNot { it.id == loan.id }
                saveLocalCache()
                renderAll()
                loansCollection.document(loan.id)
                    .delete()
                    .addOnFailureListener { error ->
                        loans = loans + loan
                        saveLocalCache()
                        renderAll()
                        showFirebaseError(error)
                    }
                Toast.makeText(
                    this,
                    "Loan removed from this device. Firebase will sync when online.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showRecordPaymentDialog(loan: LoanRecord) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val balanceLabel = TextView(this).apply {
            text = "Remaining balance: ${formatMoney(loan.remainingBalance)}"
            textSize = 13f
            setTextColor(Color.rgb(90, 86, 125))
        }
        content.addView(balanceLabel)

        val amountInput = EditText(this).apply {
            hint = "Payment amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.US, "%.2f", min(loan.monthlyInstallment, loan.remainingBalance)))
            setSelection(text.length)
        }
        val amountParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(12) }
        content.addView(amountInput, amountParams)

        val notesInput = EditText(this).apply {
            hint = "Payment note (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            minLines = 1
            maxLines = 3
        }
        val notesParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        content.addView(notesInput, notesParams)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Record Loan Repayment")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Record payment", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = amountInput.text.toString()
                    .trim()
                    .replace(",", "")
                    .toDoubleOrNull() ?: 0.0

                when {
                    amount <= 0.0 -> {
                        amountInput.error = "Enter a valid payment amount."
                    }
                    amount > loan.remainingBalance + 0.009 -> {
                        amountInput.error = "Payment cannot exceed the remaining balance."
                    }
                    else -> {
                        dialog.dismiss()
                        recordLoanPayment(
                            loan = loan,
                            amount = amount,
                            notes = notesInput.text.toString().trim()
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun recordLoanPayment(
        loan: LoanRecord,
        amount: Double,
        notes: String
    ) {
        val currentLoan = loans.firstOrNull { it.id == loan.id } ?: loan
        if (amount <= 0.0 || amount > currentLoan.remainingBalance + 0.009) {
            Toast.makeText(this, "Invalid repayment amount.", Toast.LENGTH_LONG).show()
            return
        }

        var amountToAllocate = amount
        val now = System.currentTimeMillis()
        val updatedSchedule = currentLoan.installments
            .sortedBy { it.dueDateMillis }
            .map { installment ->
                if (amountToAllocate <= 0.0001) {
                    installment
                } else {
                    val installmentBalance =
                        (installment.amount - installment.paidAmount).coerceAtLeast(0.0)
                    val allocation = min(installmentBalance, amountToAllocate)
                    amountToAllocate -= allocation

                    val newPaid = (installment.paidAmount + allocation).coerceAtMost(installment.amount)
                    val status = when {
                        newPaid >= installment.amount - 0.009 -> "PAID"
                        newPaid > 0.009 -> "PARTIAL"
                        else -> "UNPAID"
                    }

                    installment.copy(
                        paidAmount = newPaid,
                        status = status,
                        paidAtMillis = if (status == "PAID") now else installment.paidAtMillis
                    )
                }
            }

        val totalPaid = (currentLoan.totalPaid + amount).coerceAtMost(currentLoan.totalAmount)
        val remaining = (currentLoan.totalAmount - totalPaid).coerceAtLeast(0.0)
        val updatedLoan = currentLoan.copy(
            totalPaid = totalPaid,
            remainingBalance = remaining,
            installments = updatedSchedule
        )

        val paymentId = paymentsCollection.document().id
        val payment = LoanPayment(
            id = paymentId,
            loanId = currentLoan.id,
            provider = currentLoan.provider,
            loanName = currentLoan.name,
            amount = amount,
            dateMillis = now,
            notes = notes
        )

        loans = loans.map { if (it.id == updatedLoan.id) updatedLoan else it }
        loanPayments = (loanPayments + payment).sortedByDescending { it.dateMillis }
        saveLocalCache()
        renderAll()

        val batch = firestore.batch()
        val loanRef = loansCollection.document(updatedLoan.id)
        val paymentRef = paymentsCollection.document(paymentId)

        batch.set(
            loanRef,
            loanToFirestoreMap(updatedLoan, includeCreatedAt = false),
            SetOptions.merge()
        )
        batch.set(
            paymentRef,
            hashMapOf<String, Any>(
                "amount" to payment.amount,
                "dateMillis" to payment.dateMillis,
                "paymentDateMillis" to payment.dateMillis,
                "date" to Date(payment.dateMillis),
                "dateMillis" to payment.dateMillis,
                "dateString" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(payment.dateMillis)),
                "category" to "Loan Repayment",
                "description" to "Loan repayment: ${payment.provider} - ${payment.loanName}",
                "title" to "Loan Repayment",
                "paymentType" to "loan",
                "type" to "loan_repayment",
                "source" to "loan",
                "loanId" to payment.loanId,
                "loanProvider" to payment.provider,
                "loanName" to payment.loanName,
                "notes" to payment.notes,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit().addOnFailureListener { showFirebaseError(it) }

        Toast.makeText(
            this,
            "Repayment recorded locally. It will sync with Firebase when online.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showFullRepaymentSchedule() {
        val lines = mutableListOf<String>()
        loans.sortedBy { it.provider.lowercase(Locale.getDefault()) }
            .forEach { loan ->
                lines.add("${loan.provider} • ${loan.name}")
                loan.installments.sortedBy { it.dueDateMillis }.forEach { installment ->
                    val remaining = (installment.amount - installment.paidAmount).coerceAtLeast(0.0)
                    val state = when {
                        remaining <= 0.009 -> "Paid"
                        installment.dueDateMillis < startOfTodayMillis() -> "Overdue"
                        installment.paidAmount > 0.009 -> "Partially paid"
                        else -> "Due"
                    }
                    lines.add(
                        "  #${installment.number}  ${formatDate(installment.dueDateMillis)}  " +
                                "${formatMoney(remaining)}  •  $state"
                    )
                }
                lines.add("")
            }

        if (lines.isEmpty()) lines.add("No loans or installment schedules found.")

        val textView = TextView(this).apply {
            text = lines.joinToString("\n")
            textSize = 13f
            setTextColor(Color.rgb(55, 58, 75))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            isFillViewport = true
        }

        AlertDialog.Builder(this)
            .setTitle("Full Repayment Schedule")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun refreshFromFirestore() {
        if (userId.isBlank()) return
        loansProgress.visibility = View.VISIBLE
        loansCollection.get()
            .addOnSuccessListener { snapshot ->
                val remoteLoans = snapshot.documents.mapNotNull { documentToLoan(it) }
                val preserveLocal = snapshot.metadata.isFromCache && remoteLoans.isEmpty() && loans.isNotEmpty()
                if (!preserveLocal) {
                    loans = remoteLoans
                    saveLocalCache()
                }
                renderAll()
                loansProgress.visibility = View.GONE
                if (!snapshot.metadata.isFromCache) tvLoansError.visibility = View.GONE
            }
            .addOnFailureListener { error ->
                loansProgress.visibility = View.GONE
                showFirebaseError(error)
            }
        paymentsCollection.get()
            .addOnSuccessListener { snapshot ->
                val remotePayments = snapshot.documents.mapNotNull { documentToPayment(it) }
                    .sortedByDescending { it.dateMillis }
                val preserveLocal = snapshot.metadata.isFromCache &&
                        remotePayments.isEmpty() && loanPayments.isNotEmpty()
                if (!preserveLocal) {
                    loanPayments = remotePayments
                    saveLocalCache()
                }
                renderAll()
            }
            .addOnFailureListener { error ->
                showFirebaseError(error)
            }
    }

    private fun startFirestoreListeners() {
        loansLoading.visibility = if (loans.isEmpty()) View.VISIBLE else View.GONE
        loansProgress.visibility = if (loans.isEmpty()) View.VISIBLE else View.GONE

        loansListener = loansCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                loansLoaded = true
                updateLoadingState()
                showFirebaseError(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val remoteLoans = snapshot.documents.mapNotNull { documentToLoan(it) }
                val preserveLocalCache =
                    snapshot.metadata.isFromCache && remoteLoans.isEmpty() && loans.isNotEmpty()

                if (!preserveLocalCache) {
                    loans = remoteLoans
                    saveLocalCache()
                }
                loansLoaded = true
                updateLoadingState()
                renderAll()
                if (!snapshot.metadata.isFromCache) {
                    tvLoansError.visibility = View.GONE
                }
            }
        }

        paymentsListener = paymentsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                paymentsLoaded = true
                updateLoadingState()
                showFirebaseError(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val remotePayments = snapshot.documents.mapNotNull { documentToPayment(it) }
                    .sortedByDescending { it.dateMillis }
                val preserveLocalCache =
                    snapshot.metadata.isFromCache && remotePayments.isEmpty() && loanPayments.isNotEmpty()

                if (!preserveLocalCache) {
                    loanPayments = remotePayments
                    saveLocalCache()
                }
                paymentsLoaded = true
                updateLoadingState()
                renderAll()
                if (!snapshot.metadata.isFromCache) {
                    tvLoansError.visibility = View.GONE
                }
            }
        }
    }

    private fun updateLoadingState() {
        if (loansLoaded && paymentsLoaded) {
            loansLoading.visibility = View.GONE
            loansProgress.visibility = View.GONE
        }
    }

    private fun loadLocalCache() {
        try {
            val loansArray = org.json.JSONArray(
                cachePreferences.getString("loans_json", "[]") ?: "[]"
            )
            loans = (0 until loansArray.length()).mapNotNull { index ->
                jsonToLoan(loansArray.optJSONObject(index))
            }

            val paymentsArray = org.json.JSONArray(
                cachePreferences.getString("loan_payments_json", "[]") ?: "[]"
            )
            loanPayments = (0 until paymentsArray.length()).mapNotNull { index ->
                jsonToPayment(paymentsArray.optJSONObject(index))
            }.sortedByDescending { it.dateMillis }

            renderAll()
        } catch (_: Exception) {
            // A damaged cache should not prevent the Firebase listener from loading data.
            loans = emptyList()
            loanPayments = emptyList()
        }
    }

    private fun saveLocalCache() {
        try {
            val loansArray = org.json.JSONArray()
            loans.forEach { loan -> loansArray.put(loanToJson(loan)) }

            val paymentsArray = org.json.JSONArray()
            loanPayments.forEach { payment -> paymentsArray.put(paymentToJson(payment)) }

            cachePreferences.edit()
                .putString("loans_json", loansArray.toString())
                .putString("loan_payments_json", paymentsArray.toString())
                .apply()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Unable to update the local loan cache: ${error.localizedMessage}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loanToJson(loan: LoanRecord): org.json.JSONObject {
        val schedule = org.json.JSONArray()
        loan.installments.forEach { schedule.put(org.json.JSONObject(it.toMap())) }

        return org.json.JSONObject().apply {
            put("id", loan.id)
            put("provider", loan.provider)
            put("name", loan.name)
            put("principal", loan.principal)
            put("totalInterest", loan.totalInterest)
            put("fees", loan.fees)
            put("totalAmount", loan.totalAmount)
            put("totalPaid", loan.totalPaid)
            put("remainingBalance", loan.remainingBalance)
            put("termMonths", loan.termMonths)
            put("firstDueDateMillis", loan.firstDueDateMillis)
            put("interestRate", loan.interestRate)
            put("ratePeriod", loan.ratePeriod)
            put("interestMethod", loan.interestMethod)
            put("monthlyInstallment", loan.monthlyInstallment)
            put("notes", loan.notes)
            put("installments", schedule)
        }
    }

    private fun jsonToLoan(json: org.json.JSONObject?): LoanRecord? {
        if (json == null) return null
        val scheduleJson = json.optJSONArray("installments") ?: org.json.JSONArray()
        val schedule = (0 until scheduleJson.length()).mapNotNull { index ->
            val item = scheduleJson.optJSONObject(index) ?: return@mapNotNull null
            LoanInstallment(
                number = item.optInt("number", index + 1),
                dueDateMillis = item.optLong("dueDateMillis", 0L),
                amount = item.optDouble("amount", 0.0),
                paidAmount = item.optDouble("paidAmount", 0.0),
                status = item.optString("status", "UNPAID"),
                paidAtMillis = item.optLong("paidAtMillis", 0L)
            )
        }

        return LoanRecord(
            id = json.optString("id"),
            provider = json.optString("provider"),
            name = json.optString("name"),
            principal = json.optDouble("principal", 0.0),
            totalInterest = json.optDouble("totalInterest", 0.0),
            fees = json.optDouble("fees", 0.0),
            totalAmount = json.optDouble("totalAmount", 0.0),
            totalPaid = json.optDouble("totalPaid", 0.0),
            remainingBalance = json.optDouble("remainingBalance", 0.0),
            termMonths = json.optInt("termMonths", 0),
            firstDueDateMillis = json.optLong("firstDueDateMillis", 0L),
            interestRate = json.optDouble("interestRate", 0.0),
            ratePeriod = json.optString("ratePeriod", "Per month"),
            interestMethod = json.optString(
                "interestMethod",
                "Fixed total interest (from lender)"
            ),
            monthlyInstallment = json.optDouble("monthlyInstallment", 0.0),
            notes = json.optString("notes"),
            installments = schedule
        )
    }

    private fun paymentToJson(payment: LoanPayment): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("id", payment.id)
            put("loanId", payment.loanId)
            put("provider", payment.provider)
            put("loanName", payment.loanName)
            put("amount", payment.amount)
            put("dateMillis", payment.dateMillis)
            put("notes", payment.notes)
        }

    private fun jsonToPayment(json: org.json.JSONObject?): LoanPayment? {
        if (json == null) return null
        return LoanPayment(
            id = json.optString("id"),
            loanId = json.optString("loanId"),
            provider = json.optString("provider"),
            loanName = json.optString("loanName"),
            amount = json.optDouble("amount", 0.0),
            dateMillis = json.optLong("dateMillis", 0L),
            notes = json.optString("notes")
        )
    }

    private fun documentToLoan(document: DocumentSnapshot): LoanRecord? {
        val data = document.data ?: return null

        val provider = firstString(data, "provider", "lender", "loanProvider", "providerName")
        val name = firstString(data, "name", "loanName", "title", "description")
        val principal = numberValue(
            data["principal"] ?: data["amountBorrowed"] ?: data["principalAmount"]
        )
        val interest = numberValue(data["totalInterest"] ?: data["interestAmount"] ?: data["interest"])
        val fees = numberValue(data["fees"] ?: data["additionalFees"])
        val totalAmount = numberValue(data["totalAmount"]).takeIf { it > 0.0 }
            ?: (principal + interest + fees)
        val totalPaid = numberValue(data["totalPaid"] ?: data["amountPaid"])
        val remaining = numberValue(data["remainingBalance"]).takeIf {
            data.containsKey("remainingBalance")
        } ?: (totalAmount - totalPaid).coerceAtLeast(0.0)
        val term = numberValue(data["termMonths"] ?: data["term"] ?: data["numberOfPayments"]).toInt()
        val firstDue = millisValue(
            data["firstDueDateMillis"] ?: data["firstDueDate"] ?: data["startDate"]
        )
        val rate = numberValue(data["interestRate"] ?: data["rate"])
        val ratePeriod = firstString(data, "ratePeriod").ifBlank { "Per month" }
        val method = firstString(data, "interestMethod").ifBlank {
            "Fixed total interest (from lender)"
        }
        val monthly = numberValue(data["monthlyInstallment"] ?: data["monthlyPayment"])
            .takeIf { it > 0.0 }
            ?: if (term > 0) totalAmount / term else 0.0
        val notes = firstString(data, "notes", "description")

        val scheduleRaw = data["installments"] as? List<*>
        var schedule = scheduleRaw?.mapIndexedNotNull { index, value ->
            val map = value as? Map<*, *> ?: return@mapIndexedNotNull null
            LoanInstallment(
                number = numberValue(map["number"]).toInt().takeIf { it > 0 } ?: index + 1,
                dueDateMillis = millisValue(map["dueDateMillis"] ?: map["dueDate"]),
                amount = numberValue(map["amount"] ?: map["installmentAmount"]),
                paidAmount = numberValue(map["paidAmount"]),
                status = (map["status"]?.toString() ?: "UNPAID").uppercase(Locale.US),
                paidAtMillis = millisValue(map["paidAtMillis"] ?: map["paidAt"])
            )
        } ?: emptyList()

        if (schedule.isEmpty() && term > 0 && firstDue > 0L && totalAmount > 0.0) {
            schedule = buildInstallmentSchedule(totalAmount, term, firstDue)
        }

        return LoanRecord(
            id = document.id,
            provider = provider.ifBlank { "Unknown lender" },
            name = name.ifBlank { "Loan" },
            principal = principal,
            totalInterest = interest,
            fees = fees,
            totalAmount = totalAmount,
            totalPaid = totalPaid,
            remainingBalance = remaining.coerceAtLeast(0.0),
            termMonths = term,
            firstDueDateMillis = firstDue,
            interestRate = rate,
            ratePeriod = ratePeriod,
            interestMethod = method,
            monthlyInstallment = monthly,
            notes = notes,
            installments = schedule
        )
    }

    private fun documentToPayment(document: DocumentSnapshot): LoanPayment? {
        val data = document.data ?: return null
        val loanId = firstString(data, "loanId")
        val source = firstString(data, "source", "paymentType", "type")
        if (loanId.isBlank() && !source.contains("loan", ignoreCase = true)) {
            return null
        }

        return LoanPayment(
            id = document.id,
            loanId = loanId,
            provider = firstString(data, "loanProvider", "provider", "lender"),
            loanName = firstString(data, "loanName", "description", "title"),
            amount = numberValue(data["amount"] ?: data["paymentAmount"]),
            dateMillis = millisValue(
                data["dateMillis"] ?: data["paymentDateMillis"] ?: data["date"] ?: data["createdAt"]
            ),
            notes = firstString(data, "notes", "memo")
        )
    }

    private fun firstString(data: Map<String, Any>, vararg keys: String): String {
        keys.forEach { key ->
            val value = data[key]
            if (value != null && value !is FieldValue) {
                val text = value.toString().trim()
                if (text.isNotBlank() && text != "null") return text
            }
        }
        return ""
    }

    private fun numberValue(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(",", "").trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun millisValue(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is Timestamp -> value.toDate().time
            is Date -> value.time
            is String -> {
                value.trim().toLongOrNull() ?: parseDateString(value)
            }
            else -> 0L
        }
    }

    private fun parseDateString(value: String): Long {
        val patterns = listOf("yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy")
        patterns.forEach { pattern ->
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(value)?.time ?: 0L
            } catch (_: Exception) {
                // Try the next date format.
            }
        }
        return 0L
    }

    private fun renderAll() {
        renderOverview()
        renderUpcomingInstallments()
        renderLoanList()
        renderPaymentHistory()
    }

    private fun renderOverview() {
        val principal = loans.sumOf { it.principal }
        val repaid = loans.sumOf { it.totalPaid }
        val interest = loans.sumOf { it.totalInterest }
        val outstanding = loans.sumOf { it.remainingBalance.coerceAtLeast(0.0) }
        val originalTotal = loans.sumOf { it.totalAmount }
        val activeCount = loans.count { it.remainingBalance > 0.009 }
        val progress = if (originalTotal > 0.0) {
            ((repaid / originalTotal) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }

        tvTotalOutstandingDebt.text = formatMoney(outstanding)
        tvOutstandingDebtDescription.text =
            "$activeCount active loan${if (activeCount == 1) "" else "s"} remaining"
        tvTotalLoanPrincipal.text = formatMoney(principal)
        tvTotalLoanRepaid.text = formatMoney(repaid)
        tvTotalLoanInterest.text = formatMoney(interest)
        tvActiveLoanCount.text = activeCount.toString()
        progressOverallLoanRepayment.progress = progress
        tvOverallLoanProgress.text = "$progress% of total loan amount repaid"
    }

    private data class UpcomingItem(
        val loan: LoanRecord,
        val installment: LoanInstallment
    )

    private fun getUpcomingItems(): List<UpcomingItem> {
        val today = startOfTodayMillis()
        return loans.flatMap { loan ->
            loan.installments
                .filter {
                    it.dueDateMillis >= today &&
                            it.amount - it.paidAmount > 0.009 &&
                            loan.remainingBalance > 0.009
                }
                .map { UpcomingItem(loan, it) }
        }.sortedBy { it.installment.dueDateMillis }
    }

    private fun renderUpcomingInstallments() {
        val items = getUpcomingItems()
        val total = items.sumOf {
            (it.installment.amount - it.installment.paidAmount).coerceAtLeast(0.0)
        }

        tvUpcomingLoanCount.text = "${items.size} upcoming"
        tvUpcomingInstallmentTotal.text = formatMoney(total)

        upcomingInstallmentContainer.removeAllViews()
        if (items.isEmpty()) {
            tvUpcomingLoanEmpty.visibility = View.VISIBLE
            upcomingInstallmentContainer.addView(tvUpcomingLoanEmpty)
            return
        }

        tvUpcomingLoanEmpty.visibility = View.GONE
        items.take(6).forEach { item ->
            val balance = (item.installment.amount - item.installment.paidAmount).coerceAtLeast(0.0)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = roundedBackground(Color.rgb(248, 248, 252), 0, 10f)
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = "${item.loan.provider} • ${item.loan.name}"
                textSize = 12f
                setTextColor(Color.rgb(45, 47, 65))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val amount = TextView(this).apply {
                text = formatMoney(balance)
                textSize = 12f
                setTextColor(Color.rgb(213, 138, 54))
                setTypeface(typeface, Typeface.BOLD)
            }
            top.addView(amount)
            row.addView(top)

            val due = TextView(this).apply {
                text = "Installment #${item.installment.number} • Due ${formatDate(item.installment.dueDateMillis)}"
                textSize = 10f
                setTextColor(Color.rgb(133, 139, 158))
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(due)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
            upcomingInstallmentContainer.addView(row, params)
        }
    }

    private fun renderLoanList() {
        if (!::loanListContainer.isInitialized) return

        val query = etLoanSearch.text.toString().trim().lowercase(Locale.getDefault())
        val statusFilter = spLoanStatusFilter.selectedItem?.toString() ?: "All Loans"

        val filtered = loans.filter { loan ->
            val matchesText = query.isBlank() ||
                    loan.provider.lowercase(Locale.getDefault()).contains(query) ||
                    loan.name.lowercase(Locale.getDefault()).contains(query) ||
                    loan.notes.lowercase(Locale.getDefault()).contains(query)

            val status = loanStatus(loan)
            val matchesStatus = when (statusFilter) {
                "Active" -> status == "Active"
                "Paid Off" -> status == "Paid Off"
                "Overdue" -> status == "Overdue"
                else -> true
            }
            matchesText && matchesStatus
        }.sortedByDescending { it.remainingBalance }

        loanListContainer.removeAllViews()
        tvLoanRecordCount.text =
            "${filtered.size} loan ${if (filtered.size == 1) "record" else "records"}"

        loansEmptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { loan ->
            loanListContainer.addView(
                createLoanCard(loan),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun createLoanCard(loan: LoanRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(13))
            background = roundedBackground(Color.WHITE, Color.rgb(232, 233, 241), 14f)
            elevation = dp(1).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = TextView(this).apply {
            text = loan.name
            textSize = 14f
            setTextColor(Color.rgb(32, 35, 52))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }
        val provider = TextView(this).apply {
            text = loan.provider
            textSize = 11f
            setTextColor(Color.rgb(133, 139, 158))
            setPadding(0, dp(3), 0, 0)
        }
        titleColumn.addView(name)
        titleColumn.addView(provider)
        header.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val status = loanStatus(loan)
        val statusView = TextView(this).apply {
            text = status
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setTextColor(
                when (status) {
                    "Paid Off" -> Color.rgb(20, 139, 102)
                    "Overdue" -> Color.rgb(195, 75, 83)
                    else -> Color.rgb(213, 138, 54)
                }
            )
            background = roundedBackground(
                when (status) {
                    "Paid Off" -> Color.rgb(231, 247, 240)
                    "Overdue" -> Color.rgb(255, 239, 239)
                    else -> Color.rgb(255, 246, 232)
                },
                0,
                20f
            )
        }
        header.addView(statusView)
        card.addView(header)

        val balance = TextView(this).apply {
            text = formatMoney(loan.remainingBalance)
            textSize = 22f
            setTextColor(Color.rgb(101, 88, 211))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(balance)

        val details = TextView(this).apply {
            text = "Outstanding balance • ${formatMoney(loan.totalPaid)} repaid of ${formatMoney(loan.totalAmount)}"
            textSize = 10f
            setTextColor(Color.rgb(133, 139, 158))
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(details)

        val percent = if (loan.totalAmount > 0.0) {
            ((loan.totalPaid / loan.totalAmount) * 100.0).toInt().coerceIn(0, 100)
        } else 0
        val progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            this.progress = percent
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(101, 88, 211))
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.rgb(235, 234, 245))
        }
        card.addView(
            progress,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply {
                topMargin = dp(10)
            }
        )

        val nextDue = loan.installments
            .filter { it.amount - it.paidAmount > 0.009 }
            .minByOrNull { it.dueDateMillis }
        val dueLabel = TextView(this).apply {
            text = if (nextDue == null) {
                "No unpaid installments"
            } else {
                "Next unpaid installment: ${formatDate(nextDue.dueDateMillis)} • " +
                        formatMoney((nextDue.amount - nextDue.paidAmount).coerceAtLeast(0.0))
            }
            textSize = 10f
            setTextColor(Color.rgb(133, 139, 158))
            setPadding(0, dp(8), 0, dp(9))
        }
        card.addView(dueLabel)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val editButton = actionText("Edit", Color.rgb(101, 88, 211), Color.rgb(239, 237, 255)) {
            showEditLoanForm(loan)
        }
        val payButton = actionText(
            "Record Payment",
            Color.WHITE,
            Color.rgb(101, 88, 211)
        ) {
            if (loan.remainingBalance > 0.009) {
                showRecordPaymentDialog(loan)
            } else {
                Toast.makeText(this@LoansActivity, "This loan is already paid off.", Toast.LENGTH_SHORT).show()
            }
        }
        val deleteButton = actionText(
            "Delete",
            Color.rgb(196, 79, 86),
            Color.rgb(255, 240, 240)
        ) {
            showDeleteLoanConfirmation(loan)
        }

        actions.addView(editButton, weightedButtonParams())
        actions.addView(payButton, weightedButtonParams(dp(7)))
        actions.addView(deleteButton, weightedButtonParams(dp(7)))
        card.addView(actions)

        return card
    }

    private fun renderPaymentHistory() {
        loanPaymentHistoryContainer.removeAllViews()
        val sorted = loanPayments.sortedByDescending { it.dateMillis }

        if (sorted.isEmpty()) {
            tvLoanPaymentHistoryEmpty.visibility = View.VISIBLE
            loanPaymentHistoryContainer.addView(tvLoanPaymentHistoryEmpty)
            return
        }

        tvLoanPaymentHistoryEmpty.visibility = View.GONE
        sorted.take(30).forEachIndexed { index, payment ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(10), dp(5), dp(10))
            }

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(this).apply {
                text = "${payment.provider.ifBlank { "Loan" }} • ${payment.loanName.ifBlank { "Repayment" }}"
                textSize = 11f
                setTextColor(Color.rgb(48, 51, 68))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            }
            val date = TextView(this).apply {
                text = buildString {
                    append(formatDate(payment.dateMillis))
                    if (payment.notes.isNotBlank()) append(" • ${payment.notes}")
                }
                textSize = 9f
                setTextColor(Color.rgb(133, 139, 158))
                setPadding(0, dp(3), 0, 0)
            }
            details.addView(title)
            details.addView(date)
            row.addView(
                details,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val amount = TextView(this).apply {
                text = "-${formatMoney(payment.amount)}"
                textSize = 12f
                setTextColor(Color.rgb(20, 139, 102))
                setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(amount)
            loanPaymentHistoryContainer.addView(row)

            if (index < sorted.size - 1 && index < 29) {
                val divider = View(this).apply {
                    setBackgroundColor(Color.rgb(239, 240, 245))
                }
                loanPaymentHistoryContainer.addView(
                    divider,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                )
            }
        }
    }

    private fun loanStatus(loan: LoanRecord): String {
        if (loan.remainingBalance <= 0.009) return "Paid Off"
        val today = startOfTodayMillis()
        val overdue = loan.installments.any { installment ->
            installment.dueDateMillis < today &&
                    installment.amount - installment.paidAmount > 0.009
        }
        return if (overdue) "Overdue" else "Active"
    }

    private fun actionText(
        label: String,
        textColor: Int,
        backgroundColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            background = roundedBackground(backgroundColor, 0, 9f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            minHeight = dp(40)
        }
    }

    private fun weightedButtonParams(startMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            dp(42),
            1f
        ).apply {
            if (startMargin > 0) leftMargin = startMargin
        }
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }
    }

    private fun startOfTodayMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatDate(millis: Long): String {
        if (millis <= 0L) return "Date not set"
        return dateFormatter.format(Date(millis))
    }

    private fun formatMoney(amount: Double): String =
        currencyFormatter.format(amount.coerceAtLeast(0.0))

    private fun getAmount(editText: EditText): Double {
        return editText.text.toString()
            .trim()
            .replace(",", "")
            .toDoubleOrNull() ?: 0.0
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val inputMethodManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(
            currentFocus?.windowToken,
            0
        )
    }

    private fun showFirebaseError(error: Exception) {
        tvLoansError.text =
            "Firebase could not refresh this screen. Your locally saved records are still available. " +
                    (error.localizedMessage ?: "")
        tvLoansError.visibility = View.VISIBLE
        loansLoading.visibility = View.GONE
        loansProgress.visibility = View.GONE
    }

    override fun onDestroy() {
        loansListener?.remove()
        paymentsListener?.remove()
        super.onDestroy()
    }
}

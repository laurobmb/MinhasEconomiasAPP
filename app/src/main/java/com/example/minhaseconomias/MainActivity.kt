package com.example.minhaseconomias

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.minhaseconomias.ui.theme.MinhaseconomiasTheme
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone

//region Data & API Classes (Sem Alterações)
sealed class SyncResult {
    object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
}

sealed class AppScreen(val route: String) {
    object Home : AppScreen("home")
    object TransactionDetail : AppScreen("transaction_detail/{transactionId}?type={type}") {
        fun createRoute(transactionId: Int, type: String? = null): String {
            val baseRoute = "transaction_detail/$transactionId"
            return if (type != null) "$baseRoute?type=$type" else baseRoute
        }
    }
    object Transferencia : AppScreen("transferencia")
    object Filter : AppScreen("filter")
}


data class MovimentacaoApiDto(@SerializedName("id") val id: Int, @SerializedName("data_ocorrencia") val dataOcorrencia: String, @SerializedName("descricao") val descricao: String, @SerializedName("valor") val valor: Double, @SerializedName("categoria") val categoria: String, @SerializedName("conta") val conta: String)
data class MovimentacoesResponse(@SerializedName("movimentacoes") val movimentacoes: List<MovimentacaoApiDto>)
data class ContaSaldo(@SerializedName("nome") val nome: String, @SerializedName("saldo_atual") val saldoAtual: Double)
data class SaldosResponse(@SerializedName("saldoGeral") val saldoGeral: Double, @SerializedName("saldosContas") val saldosContas: List<ContaSaldo>)

class MovimentacaoRepository(
    private val dao: MovimentacaoDao,
    private val api: ApiService,
    private val categoriaDao: CategoriaDao,
    private val contaDao: ContaDao
) {
    val todasMovimentacoes: Flow<List<Movimentacao>> = dao.getAll()
    val todasCategorias: Flow<List<CategoriaSugerida>> = categoriaDao.getAll()
    val todasContas: Flow<List<ContaSugerida>> = contaDao.getAll()

    suspend fun addOrUpdateMovimentacao(mov: Movimentacao) {
        dao.insertAll(listOf(mov.copy(isSynced = false)))
    }

    suspend fun deleteMovimentacao(mov: Movimentacao) {
        if (mov.serverId == null) {
            dao.deleteByLocalId(mov.localId)
        } else {
            dao.update(mov.copy(isDeleted = true, isSynced = false))
        }
    }

    suspend fun fetchSaldosDoServidor(): Response<SaldosResponse> {
        return api.getSaldos()
    }

    suspend fun syncWithServer(): SyncResult {
        Log.d("Repository", "Iniciando sincronização...")
        val unsynced = dao.getUnsynced()
        if (unsynced.isNotEmpty()) {
            Log.d("Repository", "Enviando ${unsynced.size} transações não sincronizadas.")
            unsynced.forEach { mov ->
                try {
                    val response = if (mov.serverId != null) {
                        api.updateMovimentacao(mov.serverId!!, mov.dataOcorrencia, mov.descricao, mov.valor.toString(), mov.categoria, mov.conta)
                    } else {
                        api.addMovimentacao(mov.dataOcorrencia, mov.descricao, mov.valor.toString(), mov.categoria, mov.conta)
                    }
                    if (response.isSuccessful || response.code() == 302) {
                        Log.d("Repository", "Sucesso no envio do item local: ${mov.localId}")
                    }
                } catch (e: Exception) {
                    Log.e("Repository", "Falha ao enviar transação local ${mov.localId}", e)
                }
            }
        }
        val toDelete = dao.getDeleted()
        if (toDelete.isNotEmpty()) {
            Log.d("Repository", "Enviando ${toDelete.size} exclusões.")
            toDelete.forEach { mov ->
                try {
                    val response = api.deleteMovimentacao(mov.serverId!!)
                    if (response.isSuccessful) {
                        dao.deleteByLocalId(mov.localId)
                        Log.d("Repository", "Transação ${mov.serverId} excluída com sucesso.")
                    }
                } catch (e: Exception) {
                    Log.e("Repository", "Falha ao excluir transação ${mov.serverId}", e)
                }
            }
        }

        return try {
            val serverResponse = api.getMovimentacoes(null, null, null, null, null)
            if (serverResponse.isSuccessful) {
                val serverMovs = serverResponse.body()?.movimentacoes ?: emptyList()
                Log.d("Repository", "Recebidas ${serverMovs.size} transações do servidor. Substituindo dados locais.")
                val freshLocalMovs = serverMovs.map { Movimentacao(serverId = it.id, dataOcorrencia = it.dataOcorrencia, descricao = it.descricao, valor = it.valor, categoria = it.categoria, conta = it.conta, isSynced = true) }
                dao.deleteAll()
                dao.insertAll(freshLocalMovs)
                if (serverMovs.isNotEmpty()) {
                    val categoriasUnicas = serverMovs.map { it.categoria }.distinct().filter { it.isNotBlank() }
                    val contasUnicas = serverMovs.map { it.conta }.distinct().filter { it.isNotBlank() }
                    categoriaDao.insertAll(categoriasUnicas.map { CategoriaSugerida(nome = it) })
                    contaDao.insertAll(contasUnicas.map { ContaSugerida(nome = it) })
                }
                Log.d("Repository", "Sincronização destrutiva concluída com sucesso.")
                SyncResult.Success
            } else {
                Log.e("Repository", "Erro na resposta do servidor: ${serverResponse.code()}")
                SyncResult.Error("Falha ao buscar dados do servidor.")
            }
        } catch (e: Exception) {
            Log.e("Repository", "Falha de conexão durante a sincronização.", e)
            SyncResult.Error("Falha de conexão com o servidor.")
        }
    }
}
//endregion

class MovimentacaoViewModel(private val repository: MovimentacaoRepository) : ViewModel() {
    val movimentacoes: Flow<List<Movimentacao>> = repository.todasMovimentacoes
    private val _saldosData = mutableStateOf<SaldosResponse?>(null)
    val saldosData: State<SaldosResponse?> = _saldosData

    private val _searchDescricao = MutableStateFlow("")
    val searchDescricao: StateFlow<String> = _searchDescricao

    private val _startDate = MutableStateFlow("")
    val startDate: StateFlow<String> = _startDate

    private val _endDate = MutableStateFlow("")
    val endDate: StateFlow<String> = _endDate

    private val _selectedCategory = MutableStateFlow("")
    val selectedCategory: StateFlow<String> = _selectedCategory

    private val _selectedAccount = MutableStateFlow("")
    val selectedAccount: StateFlow<String> = _selectedAccount


    val categoriasSugeridas: StateFlow<List<String>> = repository.todasCategorias.map { list -> list.map { it.nome } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val contasSugeridas: StateFlow<List<String>> = repository.todasContas.map { list -> list.map { it.nome } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class FilterParams(val desc: String, val start: String, val end: String, val cat: String, val acc: String)

    private val allFilters = combine(
        _searchDescricao,
        _startDate,
        _endDate,
        _selectedCategory,
        _selectedAccount
    ) { desc, start, end, cat, acc ->
        FilterParams(desc, start, end, cat, acc)
    }

    val filteredMovimentacoes: StateFlow<List<Movimentacao>> = allFilters.combine(movimentacoes) { params, movs ->
        movs.filter { mov ->
            val matchesDesc = if (params.desc.isBlank()) true else mov.descricao.contains(params.desc, ignoreCase = true)
            val matchesStartDate = if (params.start.isBlank()) true else mov.dataOcorrencia >= params.start
            val matchesEndDate = if (params.end.isBlank()) true else mov.dataOcorrencia <= params.end
            val matchesCategory = if (params.cat.isBlank()) true else mov.categoria == params.cat
            val matchesAccount = if (params.acc.isBlank()) true else mov.conta == params.acc
            matchesDesc && matchesStartDate && matchesEndDate && matchesCategory && matchesAccount
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- NOVO BLOCO ---
    // Este bloco será executado quando o ViewModel for criado pela primeira vez.
    init {
        setDefaultDateFilters()
    }

    // --- NOVA FUNÇÃO ---
    // Define os filtros de data para o primeiro e último dia do mês corrente.
    private fun setDefaultDateFilters() {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // Define a data para o primeiro dia do mês corrente
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        _startDate.value = dateFormat.format(calendar.time)

        // Define a data para o último dia do mês corrente
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        _endDate.value = dateFormat.format(calendar.time)
    }

    fun setSearchDescricao(descricao: String) { _searchDescricao.value = descricao }
    fun setStartDate(date: String) { _startDate.value = date }
    fun setEndDate(date: String) { _endDate.value = date }
    fun setSelectedCategory(category: String) { _selectedCategory.value = category }
    fun setSelectedAccount(account: String) { _selectedAccount.value = account }

    fun clearFilters() {
        _searchDescricao.value = ""
        _startDate.value = ""
        _endDate.value = ""
        _selectedCategory.value = ""
        _selectedAccount.value = ""
    }


    fun addOrUpdateMovimentacao(mov: Movimentacao) = viewModelScope.launch { repository.addOrUpdateMovimentacao(mov) }
    fun addTransferencia(data: String, descricao: String, valor: String, origem: String, destino: String, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            if (origem.isBlank() || destino.isBlank() || valor.isBlank() || data.isBlank()) {
                onError("Todos os campos são obrigatórios."); return@launch
            }
            if (origem == destino) {
                onError("A conta de origem e destino não podem ser a mesma."); return@launch
            }
            val tempApiService = ApiClient.create(ServerUrlManager.getUrl())
            val response = tempApiService.addTransferencia(data, descricao, valor, origem, destino)
            if (response.isSuccessful || response.code() == 302) {
                Log.d("ViewModel", "Transferência enviada com sucesso."); onSuccess()
            } else {
                Log.e("ViewModel", "Erro ao enviar transferência: ${response.errorBody()?.string()}"); onError("Erro ao processar a transferência no servidor.")
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Exceção ao enviar transferência", e); onError("Falha na conexão ao tentar enviar a transferência.")
        }
    }
    fun deleteMovimentacao(mov: Movimentacao) = viewModelScope.launch { repository.deleteMovimentacao(mov) }

    suspend fun sync(): SyncResult {
        val result = repository.syncWithServer()
        if (result is SyncResult.Success) {
            fetchSaldos()
        }
        return result
    }

    fun fetchSaldos() = viewModelScope.launch {
        try {
            val response = repository.fetchSaldosDoServidor()
            if (response.isSuccessful) {
                _saldosData.value = response.body()
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Falha ao buscar saldos", e)
        }
    }
}

//region Outras Classes e Componíveis (Sem Alterações)
class MovimentacaoViewModelFactory(private val repository: MovimentacaoRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MovimentacaoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MovimentacaoViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

interface ApiService {
    @FormUrlEncoded @POST("/login") suspend fun login(@Field("email") email: String, @Field("password") password: String): Response<Unit>
    @GET("/api/movimentacoes") suspend fun getMovimentacoes( @Query("search_descricao") searchDescricao: String?, @Query("start_date") startDate: String?, @Query("end_date") endDate: String?, @Query("category") category: String?, @Query("account") account: String?): Response<MovimentacoesResponse>
    @GET("/api/saldos") suspend fun getSaldos(): Response<SaldosResponse>
    @FormUrlEncoded @POST("/movimentacoes") suspend fun addMovimentacao(@Field("data_ocorrencia") dataOcorrencia: String, @Field("descricao") descricao: String, @Field("valor") valor: String, @Field("categoria") categoria: String, @Field("conta") conta: String, @Field("consolidado") consolidado: String = "on"): Response<Unit>
    @FormUrlEncoded @POST("/movimentacoes/update/{id}") suspend fun updateMovimentacao(@Path("id") id: Int, @Field("data_ocorrencia") dataOcorrencia: String, @Field("descricao") descricao: String, @Field("valor") valor: String, @Field("categoria") categoria: String, @Field("conta") conta: String, @Field("consolidado") consolidado: String = "on"): Response<Unit>
    @DELETE("/movimentacoes/{id}") suspend fun deleteMovimentacao(@Path("id") id: Int): Response<Unit>
    @FormUrlEncoded @POST("/movimentacoes/transferencia") suspend fun addTransferencia(@Field("data_ocorrencia") dataOcorrencia: String, @Field("descricao") descricao: String, @Field("valor") valor: String, @Field("conta_origem") contaOrigem: String, @Field("conta_destino") contaDestino: String): Response<Unit>
}

object ApiClient {
    private val cookieJar = object : CookieJar { private val cookieStore = mutableListOf<Cookie>(); override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieStore.removeAll { it.name() == "session_token" }; cookieStore.addAll(cookies) }; override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore }
    private val okHttpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()
    fun create(baseUrl: String): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerUrlManager.init(this)
        setContent {
            MinhaseconomiasTheme {
                var isLoggedIn by remember { mutableStateOf(false) }
                var serverUrl by remember { mutableStateOf(ServerUrlManager.getUrl()) }

                if (!isLoggedIn) {
                    LoginScreen(
                        initialUrl = serverUrl,
                        onLoginSuccess = { newUrl ->
                            serverUrl = newUrl
                            isLoggedIn = true
                        }
                    )
                } else {
                    val apiService = ApiClient.create(serverUrl)
                    val database = AppDatabase.getInstance(this)
                    val repository = MovimentacaoRepository(database.movimentacaoDao(), apiService, database.categoriaDao(), database.contaDao())
                    val viewModelFactory = MovimentacaoViewModelFactory(repository)
                    MinhasEconomiasApp(viewModelFactory = viewModelFactory)
                }
            }
        }
    }
}

@Composable
fun MinhasEconomiasApp(viewModelFactory: MovimentacaoViewModelFactory) {
    val navController = rememberNavController()
    val viewModel: MovimentacaoViewModel = viewModel(factory = viewModelFactory)
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }


    NavHost(navController = navController, startDestination = AppScreen.Home.route) {
        composable(AppScreen.Home.route) {
            MainScreen(
                viewModel = viewModel,
                currentScreen = currentScreen,
                onScreenChange = { newScreen -> currentScreen = newScreen },
                onNavigateToAddTransaction = { type ->
                    navController.navigate(AppScreen.TransactionDetail.createRoute(-1, type))
                },
                onNavigateToEditTransaction = { transactionId ->
                    navController.navigate(AppScreen.TransactionDetail.createRoute(transactionId))
                },
                onNavigateToTransfer = {
                    navController.navigate(AppScreen.Transferencia.route)
                },
                onNavigateToFilter = {
                    navController.navigate(AppScreen.Filter.route)
                }
            )
        }
        composable(
            route = AppScreen.TransactionDetail.route,
            arguments = listOf(
                navArgument("transactionId") { type = NavType.IntType },
                navArgument("type") { type = NavType.StringType; nullable = true }
            )
        ) { backStackEntry ->
            val transactionId = backStackEntry.arguments?.getInt("transactionId") ?: -1
            val transactionType = backStackEntry.arguments?.getString("type")
            TransactionDetailScreen(
                viewModel = viewModel,
                transactionId = transactionId,
                transactionType = transactionType,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(AppScreen.Transferencia.route) {
            TransferenciaScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(AppScreen.Filter.route) {
            FilterScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onApplyFilters = {
                    currentScreen = Screen.Transactions
                    navController.popBackStack()
                }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MovimentacaoViewModel,
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    onNavigateToAddTransaction: (type: String) -> Unit,
    onNavigateToEditTransaction: (Int) -> Unit,
    onNavigateToTransfer: () -> Unit,
    onNavigateToFilter: () -> Unit
) {
    var isSyncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.fetchSaldos()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.label) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                val result = viewModel.sync()
                                isSyncing = false
                                val message = when (result) {
                                    is SyncResult.Success -> "Sincronização concluída com sucesso!"
                                    is SyncResult.Error -> "Falha na sincronização: ${result.message}"
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Sync, "Sincronizar")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(Screen.Dashboard, Screen.Transactions).forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen.route == screen.route,
                        onClick = { onScreenChange(screen) },
                        icon = { Icon(screen.icon, screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            MultiActionFloatingActionButton(
                onExpenseClick = { onNavigateToAddTransaction("despesa") },
                onIncomeClick = { onNavigateToAddTransaction("receita") },
                onTransferClick = onNavigateToTransfer,
                onFilterClick = onNavigateToFilter
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                is Screen.Dashboard -> DashboardScreen(viewModel) { onScreenChange(Screen.Transactions) }
                is Screen.Transactions -> TransactionsScreen(
                    viewModel = viewModel,
                    onEditClick = { mov -> onNavigateToEditTransaction(mov.localId) }
                )
            }
        }
    }
}


@Composable
fun LoginScreen(initialUrl: String, onLoginSuccess: (newUrl: String) -> Unit) {
    var serverUrl by remember { mutableStateOf(initialUrl) }
    var email by remember { mutableStateOf("lauro@localnet.com") }
    var password by remember { mutableStateOf("1q2w3e") }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(painter = painterResource(id = R.drawable.minhas_economias), "Logo", Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            Text("Minhas Economias", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Endereço do Servidor") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    coroutineScope.launch {
                        try {
                            ServerUrlManager.setUrl(serverUrl)
                            val tempApiService = ApiClient.create(serverUrl)
                            val response = tempApiService.login(email, password)
                            if (response.isSuccessful || response.code() == 302) {
                                onLoginSuccess(serverUrl)
                            } else {
                                errorMessage = "Dados inválidos ou servidor incorreto."
                            }
                        } catch (e: Exception) {
                            errorMessage = "Falha de conexão. Verifique o endereço."
                            Log.e("LoginScreen", "Erro de rede", e)
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), Color.White)
                } else {
                    Text("Entrar")
                }
            }
        }
    }
}


@Composable
fun DashboardScreen(viewModel: MovimentacaoViewModel, onNavigateToTransactions: () -> Unit) {
    val saldosData by viewModel.saldosData
    if (saldosData == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SaldoGeralCard(saldosData!!.saldoGeral, onClick = onNavigateToTransactions) }
            items(saldosData!!.saldosContas) { conta -> SaldoContaItem(conta) }
        }
    }
}

@Composable
fun SaldoGeralCard(saldo: Double, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), elevation = CardDefaults.cardElevation(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Saldo Geral", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(8.dp))
            Text(text = formatCurrency(saldo), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Clique para ver as transações", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun SaldoContaItem(conta: ContaSaldo) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(conta.nome, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(formatCurrency(conta.saldoAtual), color = if (conta.saldoAtual < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TransactionsScreen(viewModel: MovimentacaoViewModel, onEditClick: (Movimentacao) -> Unit) {
    val movimentacoes by viewModel.filteredMovimentacoes.collectAsState()

    if (movimentacoes.isEmpty()){
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center){
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.SearchOff, contentDescription = "Sem resultados", modifier = Modifier.size(64.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Nenhuma transação encontrada.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                Text("Tente ajustar seus filtros.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
    } else {
        LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
            items(movimentacoes, key = { it.localId }) { mov ->
                TransactionItem(mov, onEditClick = { onEditClick(mov) }, onDeleteClick = { viewModel.deleteMovimentacao(mov) })
            }
        }
    }
}

@Composable
fun TransactionItem(mov: Movimentacao, onEditClick: (Movimentacao) -> Unit, onDeleteClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onEditClick(mov) }, elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!mov.isSynced) {
                Icon(imageVector = Icons.Default.CloudOff, contentDescription = "Não sincronizado", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(end = 8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("#${mov.serverId ?: mov.localId} - ${mov.descricao}", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Data: ${mov.dataOcorrencia} | ${mov.categoria} | ${mov.conta}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatCurrency(mov.valor), color = if (mov.valor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Column {
                IconButton(onClick = { onEditClick(mov) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Edit, "Editar") }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Delete, "Excluir", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Confirmar Exclusão") }, text = { Text("Tem certeza que deseja excluir a transação '${mov.descricao}'?") }, confirmButton = { Button(onClick = { onDeleteClick(); showDeleteDialog = false }) { Text("Excluir") } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") } })
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(viewModel: MovimentacaoViewModel, transactionId: Int, transactionType: String?, onNavigateBack: () -> Unit) {
    val isEditMode = transactionId != -1
    val movimentacaoToEdit by produceState<Movimentacao?>(initialValue = null, key1 = transactionId) {
        if (isEditMode) {
            value = viewModel.movimentacoes.first().find { it.localId == transactionId }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Editar Transação" else "Adicionar Transação") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } }
            )
        }
    ) { innerPadding ->
        if (isEditMode && movimentacaoToEdit == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            TransactionSheetContent(
                modifier = Modifier.padding(innerPadding),
                viewModel = viewModel,
                movimentacaoToEdit = movimentacaoToEdit,
                initialTransactionType = transactionType,
                onSuccess = onNavigateBack
            )
        }
    }
}

@Composable
fun TransactionSheetContent(modifier: Modifier = Modifier, viewModel: MovimentacaoViewModel, movimentacaoToEdit: Movimentacao?, initialTransactionType: String?, onSuccess: () -> Unit) {
    var descricao by remember(movimentacaoToEdit) { mutableStateOf(movimentacaoToEdit?.descricao ?: "") }
    var valor by remember(movimentacaoToEdit) { mutableStateOf(if (movimentacaoToEdit != null) kotlin.math.abs(movimentacaoToEdit.valor).toString().replace('.', ',') else "") }
    var categoria by remember(movimentacaoToEdit) { mutableStateOf(movimentacaoToEdit?.categoria ?: "") }
    var conta by remember(movimentacaoToEdit) { mutableStateOf(movimentacaoToEdit?.conta ?: "") }
    var isDespesa by remember(movimentacaoToEdit, initialTransactionType) {
        mutableStateOf(
            when {
                movimentacaoToEdit != null -> movimentacaoToEdit.valor < 0
                initialTransactionType != null -> initialTransactionType == "despesa"
                else -> true
            }
        )
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val categoriasSugeridas by viewModel.categoriasSugeridas.collectAsState()
    val contasSugeridas by viewModel.contasSugeridas.collectAsState()
    LazyColumn(
        modifier = modifier.padding(16.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column {
                OutlinedTextField(value = descricao, onValueChange = { descricao = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = valor, onValueChange = { valor = it }, label = { Text("Valor (Ex: 150,75)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isDespesa, onClick = { isDespesa = true })
                    Text("Despesa", modifier = Modifier.padding(start = 4.dp, end = 16.dp))
                    RadioButton(selected = !isDespesa, onClick = { isDespesa = false })
                    Text("Receita", modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                AutoCompleteTextField(value = categoria, onValueChange = { categoria = it }, label = "Categoria", suggestions = categoriasSugeridas)
                Spacer(Modifier.height(8.dp))
                AutoCompleteTextField(value = conta, onValueChange = { conta = it }, label = "Conta", suggestions = contasSugeridas)
                Spacer(Modifier.height(24.dp))
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(
                    onClick = {
                        isLoading = true; errorMessage = null; val valorNumerico = valor.replace(",", ".").toDoubleOrNull()
                        if (valorNumerico == null) { errorMessage = "Valor inválido."; isLoading = false; return@Button }
                        val valorFinal = if (isDespesa) -kotlin.math.abs(valorNumerico) else kotlin.math.abs(valorNumerico)
                        val mov = movimentacaoToEdit?.copy(descricao = descricao, valor = valorFinal, categoria = categoria, conta = conta) ?: Movimentacao(serverId = null, dataOcorrencia = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()), descricao = descricao, valor = valorFinal, categoria = categoria, conta = conta)
                        viewModel.addOrUpdateMovimentacao(mov); onSuccess()
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !isLoading
                ) { if (isLoading) { CircularProgressIndicator(Modifier.size(24.dp), Color.White) } else { Text("Salvar") } }
            }
        }
    }
}

@Composable
fun MultiActionFloatingActionButton(
    onExpenseClick: () -> Unit,
    onIncomeClick: () -> Unit,
    onTransferClick: () -> Unit,
    onFilterClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AnimatedVisibility(visible = isExpanded) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { onFilterClick(); isExpanded = false },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) { Icon(Icons.Default.FilterAlt, contentDescription = "Filtrar") }

                SmallFloatingActionButton(
                    onClick = { onTransferClick(); isExpanded = false },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) { Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = "Transferência") }

                SmallFloatingActionButton(
                    onClick = { onIncomeClick(); isExpanded = false },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) { Icon(Icons.Filled.Add, contentDescription = "Receita") }

                SmallFloatingActionButton(
                    onClick = { onExpenseClick(); isExpanded = false },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) { Icon(Icons.Filled.Remove, contentDescription = "Despesa") }
            }
        }
        FloatingActionButton(onClick = { isExpanded = !isExpanded }) {
            Icon(if (isExpanded) Icons.Filled.Close else Icons.Filled.Add, contentDescription = if (isExpanded) "Fechar menu" else "Abrir menu")
        }
    }
}

fun formatCurrency(value: Double): String {
    return NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Dashboard)
    object Transactions : Screen("transactions", "Transações", Icons.Filled.SwapHoriz)
}

@Composable
fun AutoCompleteTextField(value: String, onValueChange: (String) -> Unit, label: String, suggestions: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }; val filteredSuggestions = remember(value, suggestions) { if (value.isBlank()) { suggestions } else { suggestions.filter { it.contains(value, ignoreCase = true) } } }; Box(modifier = modifier) { OutlinedTextField(value = value, onValueChange = { onValueChange(it); expanded = true }, label = { Text(label) }, modifier = Modifier.fillMaxWidth()); DropdownMenu(expanded = expanded && filteredSuggestions.isNotEmpty(), onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth()) { filteredSuggestions.forEach { suggestion -> DropdownMenuItem(text = { Text(suggestion) }, onClick = { onValueChange(suggestion); expanded = false }) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferenciaScreen(viewModel: MovimentacaoViewModel, onNavigateBack: () -> Unit) {
    var data by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    var descricao by remember { mutableStateOf("") }
    var valor by remember { mutableStateOf("") }
    var contaOrigem by remember { mutableStateOf("") }
    var contaDestino by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val contasSugeridas by viewModel.contasSugeridas.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nova Transferência") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                OutlinedTextField(value = data, onValueChange = { data = it }, label = { Text("Data") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = descricao, onValueChange = { descricao = it }, label = { Text("Descrição (Opcional)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = valor, onValueChange = { valor = it }, label = { Text("Valor (Ex: 250,50)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                AutoCompleteTextField(value = contaOrigem, onValueChange = { contaOrigem = it }, label = "Conta de Origem", suggestions = contasSugeridas)
                Spacer(Modifier.height(8.dp))
                AutoCompleteTextField(value = contaDestino, onValueChange = { contaDestino = it }, label = "Conta de Destino", suggestions = contasSugeridas)
                Spacer(Modifier.height(24.dp))
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        viewModel.addTransferencia(data, descricao, valor, contaOrigem, contaDestino,
                            onSuccess = {
                                coroutineScope.launch {
                                    viewModel.sync()
                                    onNavigateBack()
                                }
                            },
                            onError = { errorMsg ->
                                errorMessage = errorMsg
                                isLoading = false
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(24.dp), Color.White)
                    } else {
                        Text("Realizar Transferência")
                    }
                }
            }
        }
    }
}

// NOVA TELA DE FILTRO COM DATE PICKER
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterScreen(
    viewModel: MovimentacaoViewModel,
    onNavigateBack: () -> Unit,
    onApplyFilters: () -> Unit
) {
    val descricao by viewModel.searchDescricao.collectAsState()
    val startDate by viewModel.startDate.collectAsState()
    val endDate by viewModel.endDate.collectAsState()
    val categoria by viewModel.selectedCategory.collectAsState()
    val conta by viewModel.selectedAccount.collectAsState()

    var tempDescricao by remember { mutableStateOf(descricao) }
    var tempStartDate by remember { mutableStateOf(startDate) }
    var tempEndDate by remember { mutableStateOf(endDate) }
    var tempCategoria by remember { mutableStateOf(categoria) }
    var tempConta by remember { mutableStateOf(conta) }

    val categoriasSugeridas by viewModel.categoriasSugeridas.collectAsState()
    val contasSugeridas by viewModel.contasSugeridas.collectAsState()

    // Estados para controlar a exibição dos DatePickers
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filtrar Transações") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(value = tempDescricao, onValueChange = { tempDescricao = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            // Campo de Data de Início com DatePicker
            Box {
                OutlinedTextField(
                    value = tempStartDate,
                    onValueChange = {},
                    label = { Text("Data Início") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.CalendarToday, "Calendário") }
                )
                Box(modifier = Modifier.matchParentSize().clickable { showStartDatePicker = true })
            }

            Spacer(Modifier.height(8.dp))

            // Campo de Data de Fim com DatePicker
            Box {
                OutlinedTextField(
                    value = tempEndDate,
                    onValueChange = {},
                    label = { Text("Data Fim") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { Icon(Icons.Default.CalendarToday, "Calendário") }
                )
                Box(modifier = Modifier.matchParentSize().clickable { showEndDatePicker = true })
            }


            Spacer(Modifier.height(8.dp))
            AutoCompleteTextField(value = tempCategoria, onValueChange = { tempCategoria = it }, label = "Categoria", suggestions = categoriasSugeridas)
            Spacer(Modifier.height(8.dp))
            AutoCompleteTextField(value = tempConta, onValueChange = { tempConta = it }, label = "Conta", suggestions = contasSugeridas)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    viewModel.setSearchDescricao(tempDescricao)
                    viewModel.setStartDate(tempStartDate)
                    viewModel.setEndDate(tempEndDate)
                    viewModel.setSelectedCategory(tempCategoria)
                    viewModel.setSelectedAccount(tempConta)
                    onApplyFilters()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Filtrar")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.clearFilters()
                    tempDescricao = ""
                    tempStartDate = ""
                    tempEndDate = ""
                    tempCategoria = ""
                    tempConta = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Limpar Filtros")
            }
        }
    }

    // Lógica para exibir o DatePickerDialog de Data de Início
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                Button(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        tempStartDate = millis.toFormattedDateString()
                    }
                    showStartDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("Cancelar") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Lógica para exibir o DatePickerDialog de Data de Fim
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                Button(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        tempEndDate = millis.toFormattedDateString()
                    }
                    showEndDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("Cancelar") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

// Função de extensão para formatar a data
fun Long.toFormattedDateString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("UTC") // Importante para evitar problemas de fuso horário
    return sdf.format(Date(this))
}

//endregion
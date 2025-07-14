package com.example.minhaseconomias

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
import com.example.minhaseconomias.ui.theme.MinhaseconomiasTheme


// --- Modelos de Dados, Repositório, ViewModel, Factory e ApiService (permanecem iguais) ---
data class MovimentacaoApiDto(@SerializedName("id") val id: Int, @SerializedName("data_ocorrencia") val dataOcorrencia: String, @SerializedName("descricao") val descricao:
String, @SerializedName("valor") val valor: Double, @SerializedName("categoria") val categoria: String, @SerializedName("conta") val conta: String)
data class MovimentacoesResponse(@SerializedName("movimentacoes") val movimentacoes: List<MovimentacaoApiDto>)
data class ContaSaldo(@SerializedName("nome") val nome: String, @SerializedName("saldo_atual") val saldoAtual: Double)
data class SaldosResponse(@SerializedName("saldoGeral") val saldoGeral: Double, @SerializedName("saldosContas") val saldosContas: List<ContaSaldo>)

class MovimentacaoRepository(private val dao: MovimentacaoDao, private val api: ApiService) {
    val todasMovimentacoes: Flow<List<Movimentacao>> = dao.getAll()

    suspend fun addOrUpdateMovimentacao(mov: Movimentacao) {
        // A lógica de adicionar/atualizar localmente está correta.
        dao.insertAll(listOf(mov.copy(isSynced = false)))
    }

    suspend fun deleteMovimentacao(mov: Movimentacao) {
        // A lógica de deleção também está correta.
        if (mov.serverId == null) {
            dao.deleteByLocalId(mov.localId)
        } else {
            dao.update(mov.copy(isDeleted = true, isSynced = false))
        }
    }

    // A MUDANÇA PRINCIPAL ESTÁ AQUI
    suspend fun syncWithServer() {
        Log.d("Repository", "Iniciando sincronização inteligente...")

        // 1. Pega todas as alterações locais que ainda não foram para o servidor.
        val localUnsyncedChanges = dao.getUnsynced()
        val localDeletedItems = dao.getDeleted()

        // 2. Tenta enviar as alterações locais para o servidor (como antes).
        if (localUnsyncedChanges.isNotEmpty()) {
            Log.d("Repository", "Enviando ${localUnsyncedChanges.size} transações não sincronizadas.")
            localUnsyncedChanges.forEach { mov ->
                try {
                    val response = if (mov.serverId != null) {
                        api.updateMovimentacao(mov.serverId!!, mov.dataOcorrencia, mov.descricao, mov.valor.toString(), mov.categoria, mov.conta)
                    } else {
                        api.addMovimentacao(mov.dataOcorrencia, mov.descricao, mov.valor.toString(), mov.categoria, mov.conta)
                    }
                    // Não fazemos nada com a resposta por enquanto, a lógica abaixo cuidará do estado.
                    if(response.isSuccessful || response.code() == 302) {
                         Log.d("Repository", "Sucesso no envio do item local: ${mov.localId}")
                    }
                } catch (e: Exception) { Log.e("Repository", "Falha ao enviar transação local ${mov.localId}", e) }
            }
        }

        if (localDeletedItems.isNotEmpty()) {
            Log.d("Repository", "Enviando ${localDeletedItems.size} exclusões.")
            localDeletedItems.forEach { mov ->
                try {
                    val response = api.deleteMovimentacao(mov.serverId!!)
                    if (response.isSuccessful) {
                        dao.deleteByLocalId(mov.localId)
                        Log.d("Repository", "Transação ${mov.serverId} excluída com sucesso.")
                    }
                } catch (e: Exception) { Log.e("Repository", "Falha ao excluir transação ${mov.serverId}", e) }
            }
        }

        // 3. Busca a lista de verdade do servidor.
        try {
            val serverResponse = api.getMovimentacoes(null)
            if (serverResponse.isSuccessful) {
                val serverMovs = serverResponse.body()?.movimentacoes ?: emptyList()
                Log.d("Repository", "Recebidas ${serverMovs.size} transações do servidor.")
                val freshLocalMovs = serverMovs.map {
                    Movimentacao(
                        serverId = it.id,
                        dataOcorrencia = it.dataOcorrencia,
                        descricao = it.descricao,
                        valor = it.valor,
                        categoria = it.categoria,
                        conta = it.conta,
                        isSynced = true // Itens do servidor estão sempre sincronizados
                    )
                }

                // 4. Lógica de "Merge" Inteligente:
                // Pega as alterações locais novamente, caso algo tenha mudado durante a chamada de rede.
                val finalUnsyncedChanges = dao.getUnsynced()
                val serverIdsFromUnsynced = finalUnsyncedChanges.mapNotNull { it.serverId }.toSet()

                // Filtra a lista do servidor, removendo qualquer item que tenha uma versão local não sincronizada.
                // Isso dá prioridade à alteração local que ainda não foi confirmada.
                val filteredServerMovs = freshLocalMovs.filterNot { it.serverId in serverIdsFromUnsynced }

                // Apaga tudo...
                dao.deleteAll()
                // ...insere a lista limpa do servidor...
                dao.insertAll(filteredServerMovs)
                // ...e re-insere as alterações locais por cima, garantindo que elas não sejam perdidas.
                dao.insertAll(finalUnsyncedChanges)

                Log.d("Repository", "Banco de dados local sincronizado de forma inteligente.")
            }
        } catch (e: Exception) {
            Log.e("Repository", "Falha ao buscar e fazer merge das transações do servidor.", e)
        }
    }
}

class MovimentacaoViewModel(private val repository: MovimentacaoRepository) : ViewModel() {
    val movimentacoes: StateFlow<List<Movimentacao>> = repository.todasMovimentacoes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _isSyncing = mutableStateOf(false)
    val isSyncing: State<Boolean> = _isSyncing
    private val _saldosData = mutableStateOf<SaldosResponse?>(null)
    val saldosData: State<SaldosResponse?> = _saldosData

    init {
        sync()
    }

    fun addOrUpdateMovimentacao(mov: Movimentacao) = viewModelScope.launch {
        repository.addOrUpdateMovimentacao(mov)
    }

    fun deleteMovimentacao(mov: Movimentacao) = viewModelScope.launch {
        repository.deleteMovimentacao(mov)
    }

    fun sync() = viewModelScope.launch {
        _isSyncing.value = true
        repository.syncWithServer()
        fetchSaldos()
        _isSyncing.value = false
    }

    private fun fetchSaldos() = viewModelScope.launch {
        try {
            val response = ApiClient.instance.getSaldos()
            if (response.isSuccessful) {
                _saldosData.value = response.body()
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Falha ao buscar saldos", e)
        }
    }
}

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
    @GET("/api/movimentacoes") suspend fun getMovimentacoes(@Query("search_descricao") searchQuery: String?): Response<MovimentacoesResponse>
    @GET("/api/saldos") suspend fun getSaldos(): Response<SaldosResponse>
    @FormUrlEncoded @POST("/movimentacoes") suspend fun addMovimentacao(@Field("data_ocorrencia") dataOcorrencia: String, @Field("descricao") descricao: String, @Field("valor") valor: String, @Field("categoria") categoria: String, @Field("conta") conta: String, @Field("consolidado") consolidado: String = "on"): Response<Unit>
    @FormUrlEncoded @POST("/movimentacoes/update/{id}") suspend fun updateMovimentacao(@Path("id") id: Int, @Field("data_ocorrencia") dataOcorrencia: String, @Field("descricao") descricao: String, @Field("valor") valor: String, @Field("categoria") categoria: String, @Field("conta") conta: String, @Field("consolidado") consolidado: String ="on"): Response<Unit>
    @DELETE("/movimentacoes/{id}") suspend fun deleteMovimentacao(@Path("id") id: Int): Response<Unit>
}
object ApiClient {
    private const val BASE_URL = "http://192.168.0.221:8080"
    private val cookieJar = object : CookieJar { private val cookieStore = mutableListOf<Cookie>(); override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieStore.removeAll { it.name() == "session_token" }; cookieStore.addAll(cookies) }; override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore }
    private val okHttpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()
    val instance: ApiService by lazy { Retrofit.Builder().baseUrl(BASE_URL).client(okHttpClient).addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java) }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModelFactory: MovimentacaoViewModelFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getInstance(this)
        val repository = MovimentacaoRepository(database.movimentacaoDao(), ApiClient.instance)
        viewModelFactory = MovimentacaoViewModelFactory(repository)

        setContent {
            MinhaseconomiasTheme {
                var isLoggedIn by remember { mutableStateOf(false) }

                if (!isLoggedIn) {
                    LoginScreen(onLoginSuccess = { isLoggedIn = true })
                } else {
                    val viewModel: MovimentacaoViewModel = viewModel(factory = viewModelFactory)
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MovimentacaoViewModel) {
    val isSyncing by viewModel.isSyncing
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var showTransactionSheet by remember { mutableStateOf(false) }
    var transactionToEdit by remember { mutableStateOf<Movimentacao?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.label) },
                actions = {
                    IconButton(onClick = { viewModel.sync() }, enabled = !isSyncing) {
                        if (isSyncing) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "Sincronizar")
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
                        onClick = { currentScreen = screen },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                transactionToEdit = null
                showTransactionSheet = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Adicionar Transação")
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                is Screen.Dashboard -> DashboardScreen(
                    viewModel,
                    onNavigateToTransactions = { currentScreen = Screen.Transactions }
                )
                is Screen.Transactions -> TransactionsScreen(
                    viewModel,
                    onEditClick = { mov ->
                        transactionToEdit = mov
                        showTransactionSheet = true
                    }
                )
            }
        }
    }

    if (showTransactionSheet) {
        ModalBottomSheet(onDismissRequest = { showTransactionSheet = false }) {
            TransactionSheetContent(
                viewModel = viewModel,
                movimentacaoToEdit = transactionToEdit,
                onSuccess = {
                    showTransactionSheet = false
                    viewModel.sync()
                }
            )
        }
    }
}


@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var email by remember { mutableStateOf("lauro@localnet.com") }
    var password by remember { mutableStateOf("1q2w3e") }
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.minhas_economias),
                    contentDescription = "Logo",
                    modifier = Modifier.size(120.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("Minhas Economias", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    coroutineScope.launch {
                        try {
                            val response = ApiClient.instance.login(email, password)
                            if (response.isSuccessful || response.code() == 302) {
                                onLoginSuccess()
                            } else {
                                errorMessage = "E-mail ou senha inválidos."
                            }
                        } catch (e: Exception) {
                            errorMessage = "Erro de conexão."
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
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SaldoGeralCard(
                    saldosData!!.saldoGeral,
                    onClick = onNavigateToTransactions
                )
            }
            items(saldosData!!.saldosContas) { conta ->
                SaldoContaItem(conta)
            }
        }
    }
}

@Composable
fun SaldoGeralCard(saldo: Double, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Saldo Geral",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatCurrency(saldo),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Clique para ver as transações",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun SaldoContaItem(conta: ContaSaldo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = conta.nome,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatCurrency(conta.saldoAtual),
                color = if (conta.saldoAtual < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun TransactionsScreen(viewModel: MovimentacaoViewModel, onEditClick: (Movimentacao) -> Unit) {
    val movimentacoes by viewModel.movimentacoes.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val filteredMovimentacoes = remember(searchQuery, movimentacoes) {
        if (searchQuery.isBlank()) {
            movimentacoes
        } else {
            movimentacoes.filter {
                it.descricao.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Buscar por descrição...") },
            leadingIcon = { Icon(Icons.Filled.Search, "Ícone de busca") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )
        LazyColumn {
            items(filteredMovimentacoes, key = { it.localId }) { mov ->
                TransactionItem(
                    mov,
                    onEditClick = onEditClick,
                    onDeleteClick = { viewModel.deleteMovimentacao(mov) })
            }
        }
    }
}

@Composable
fun TransactionItem(mov: Movimentacao, onEditClick: (Movimentacao) -> Unit, onDeleteClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onEditClick(mov) },
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), // Aumentei o padding vertical
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ícone de "não sincronizado"
            if (!mov.isSynced) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Não sincronizado",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Coluna com as informações principais
            Column(Modifier.weight(1f)) {
                // LINHA 1: ID e Descrição
                Text(
                    // Mostra o ID do servidor ou o ID local se ainda não tiver um do servidor
                    text = "#${mov.serverId ?: mov.localId} - ${mov.descricao}",
                    fontWeight = FontWeight.Bold
                )

                // Espaçamento
                Spacer(modifier = Modifier.height(4.dp))

                // LINHA 2: Data, Categoria e Conta
                Text(
                    // Adicionamos a data aqui!
                    text = "Data: ${mov.dataOcorrencia} | ${mov.categoria} | ${mov.conta}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant // Cor mais suave
                )
            }

            // Valor da transação (permanece igual)
            Text(
                text = formatCurrency(mov.valor),
                // Usando a cor primária do tema para valores positivos.
                // Ela será um verde escuro no tema claro e um verde claro no tema escuro.
                color = if (mov.valor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )

            // Botões de ação (agora dentro de uma coluna para melhor alinhamento)
            Column {
                IconButton(onClick = { onEditClick(mov) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Edit, "Editar")
                }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Delete, "Excluir", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // O AlertDialog para confirmar a exclusão permanece o mesmo
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Confirmar Exclusão") },
            text = { Text("Tem certeza que deseja excluir a transação '${mov.descricao}'?") },
            confirmButton = {
                Button(onClick = {
                    onDeleteClick()
                    showDeleteDialog = false
                }) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun TransactionSheetContent(viewModel: MovimentacaoViewModel, movimentacaoToEdit: Movimentacao?, onSuccess: () -> Unit) {
    var descricao by remember { mutableStateOf(movimentacaoToEdit?.descricao ?: "") }
    var valor by remember { mutableStateOf(if (movimentacaoToEdit != null) kotlin.math.abs(movimentacaoToEdit.valor).toString().replace('.', ',') else "") }
    var categoria by remember { mutableStateOf(movimentacaoToEdit?.categoria ?: "") }
    var conta by remember { mutableStateOf(movimentacaoToEdit?.conta ?: "") }
    var isDespesa by remember { mutableStateOf(movimentacaoToEdit?.valor?.let { it < 0 } ?: true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isEditMode = movimentacaoToEdit != null
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isEditMode) "Editar Transação" else "Adicionar Nova Transação",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        OutlinedTextField(
            value = descricao,
            onValueChange = { descricao = it },
            label = { Text("Descrição") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = valor,
            onValueChange = { valor = it },
            label = { Text("Valor (Ex: 150,75)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isDespesa, onClick = { isDespesa = true })
            Text("Despesa", modifier = Modifier.padding(start = 4.dp, end = 16.dp))
            RadioButton(selected = !isDespesa, onClick = { isDespesa = false })
            Text("Receita", modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = categoria,
            onValueChange = { categoria = it },
            label = { Text("Categoria") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = conta,
            onValueChange = { conta = it },
            label = { Text("Conta") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Button(
            onClick = {
                isLoading = true
                errorMessage = null
                val valorNumerico = valor.replace(",", ".").toDoubleOrNull()
                if (valorNumerico == null) {
                    errorMessage = "Valor inválido."
                    isLoading = false
                    return@Button
                }
                val valorFinal = if (isDespesa) -kotlin.math.abs(valorNumerico) else kotlin.math.abs(valorNumerico)
                val mov = movimentacaoToEdit?.copy(
                    descricao = descricao,
                    valor = valorFinal,
                    categoria = categoria,
                    conta = conta
                ) ?: Movimentacao(
                    serverId = null,
                    dataOcorrencia = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                    descricao = descricao,
                    valor = valorFinal,
                    categoria = categoria,
                    conta = conta
                )
                viewModel.addOrUpdateMovimentacao(mov)
                onSuccess()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
            } else {
                Text("Salvar")
            }
        }
    }
}

fun formatCurrency(value: Double): String {
    return NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)
}

sealed class Screen(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Dashboard)
    object Transactions : Screen("transactions", "Transações", Icons.Filled.SwapHoriz)
}
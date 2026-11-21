package app.sterna.ui.dav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.dav.cardName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The account's server address book: search, read, create, edit and delete. Writes queue and go out when the server is reachable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(accountId: String, onBack: () -> Unit, viewModel: ContactsViewModel = viewModel()) {
    LaunchedEffect(accountId) { viewModel.bind(accountId) }
    val view by viewModel.view.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    // Back steps out of an open contact or editor before it leaves the screen.
    BackHandler(view !is ContactView.List) {
        viewModel.view.value = (view as? ContactView.Edit)?.id?.let { ContactView.Detail(it) } ?: ContactView.List
    }
    val title = when (view) {
        is ContactView.Edit -> R.string.contacts_edit.takeIf { (view as ContactView.Edit).id != null } ?: R.string.contacts_new
        else -> R.string.contacts_title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton({
                        val v = view
                        if (v is ContactView.List) onBack()
                        else viewModel.view.value = (v as? ContactView.Edit)?.id?.let { ContactView.Detail(it) } ?: ContactView.List
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dav_back)) }
                },
            )
        },
        floatingActionButton = {
            if (view is ContactView.List) {
                FloatingActionButton({ viewModel.view.value = ContactView.Edit(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contacts_new))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (pending > 0) Text(stringResource(R.string.dav_waiting_to_send), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            failed.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${stringResource(R.string.dav_failed_title)}: ${f.lastError.orEmpty()}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton({ viewModel.dismissFailed(f.id) }) { Text(stringResource(R.string.dav_dismiss)) }
                }
            }
            when (val v = view) {
                ContactView.List -> ContactList(viewModel)
                is ContactView.Detail -> ContactDetail(viewModel, v.id)
                is ContactView.Edit -> EditContact(viewModel, v.id, books)
            }
        }
    }
}

@Composable
private fun ContactList(viewModel: ContactsViewModel) {
    val rows by viewModel.contacts.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    OutlinedTextField(
        query, { viewModel.query.value = it },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.contacts_search)) }, singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) IconButton({ viewModel.query.value = "" }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.contacts_clear_search))
            }
        },
    )
    if (rows.isEmpty()) {
        Text(stringResource(if (query.isEmpty()) R.string.contacts_empty else R.string.contacts_no_match), Modifier.padding(16.dp))
        return
    }
    LazyColumn {
        items(rows, key = { it.id }) { row ->
            val name = cardName(Json.parseToJsonElement(row.json).jsonObject).ifBlank { stringResource(R.string.contacts_unnamed) }
            Text(
                name, Modifier.fillMaxWidth().clickable { viewModel.view.value = ContactView.Detail(row.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ContactDetail(viewModel: ContactsViewModel, id: String) {
    var card by remember(id) { mutableStateOf<JsonObject?>(null) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(id) { card = viewModel.card(id) }
    val c = card ?: return
    val f = formOf(c)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(f.name.ifBlank { stringResource(R.string.contacts_unnamed) }, style = MaterialTheme.typography.headlineSmall)
        listOf(R.string.contacts_email to f.emails, R.string.contacts_phone to f.phones, R.string.contacts_address to f.addresses).forEach { (label, lines) ->
            lines.forEach { Column(Modifier.padding(top = 12.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                Text(it, style = MaterialTheme.typography.bodyLarge)
            } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            IconButton({ confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.contacts_delete)) }
            IconButton({ viewModel.view.value = ContactView.Edit(id) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.contacts_edit)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.contacts_delete_confirm)) },
            confirmButton = {
                TextButton({ confirm = false; viewModel.delete(id); viewModel.view.value = ContactView.List }) { Text(stringResource(R.string.dav_delete)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.dav_cancel)) } },
        )
    }
}

@Composable
private fun EditContact(viewModel: ContactsViewModel, id: String?, books: List<BookChoice>) {
    // Read once: the editor must not be reset by a sync landing while it is open.
    var initial by remember(id) { mutableStateOf<ContactForm?>(null) }
    LaunchedEffect(id) {
        initial = if (id == null) ContactForm(bookId = viewModel.defaultBookId())
        else formOf(viewModel.card(id) ?: JsonObject(emptyMap()), viewModel.defaultBookId())
    }
    val start = initial ?: return
    ContactEditor(
        initial = start, books = books, canPickBook = id == null,
        onSave = { form ->
            viewModel.save(id, form)
            viewModel.view.value = id?.let { ContactView.Detail(it) } ?: ContactView.List
        },
        onCancel = { viewModel.view.value = id?.let { ContactView.Detail(it) } ?: ContactView.List },
    )
}

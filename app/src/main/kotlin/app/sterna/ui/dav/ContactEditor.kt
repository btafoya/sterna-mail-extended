package app.sterna.ui.dav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R

/** The fields of one contact; [initial] is what it starts as, [onSave] gets what the user made of it. */
@Composable
fun ContactEditor(
    initial: ContactForm,
    books: List<BookChoice>,
    canPickBook: Boolean,
    onSave: (ContactForm) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    // A list always ends in an empty row to type into; blanks are dropped by the save.
    var emails by rememberSaveable { mutableStateOf(initial.emails.ifEmpty { listOf("") }) }
    var phones by rememberSaveable { mutableStateOf(initial.phones.ifEmpty { listOf("") }) }
    var addresses by rememberSaveable { mutableStateOf(initial.addresses.ifEmpty { listOf("") }) }
    var bookId by rememberSaveable { mutableStateOf(initial.bookId) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.contacts_name)) }, singleLine = true)
        Lines(R.string.contacts_email, R.string.contacts_add_email, emails) { emails = it }
        Lines(R.string.contacts_phone, R.string.contacts_add_phone, phones) { phones = it }
        Lines(R.string.contacts_address, R.string.contacts_add_address, addresses) { addresses = it }
        if (canPickBook && books.size > 1) BookPicker(books, bookId) { bookId = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onCancel) { Text(stringResource(R.string.dav_cancel)) }
            TextButton({ onSave(ContactForm(name, emails, phones, addresses, bookId)) }) { Text(stringResource(R.string.dav_save)) }
        }
    }
}

@Composable
private fun Lines(label: Int, add: Int, rows: List<String>, onChange: (List<String>) -> Unit) {
    rows.forEachIndexed { i, text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { v -> onChange(rows.toMutableList().also { it[i] = v }) }, Modifier.weight(1f),
                label = { Text(stringResource(label)) }, singleLine = true,
            )
            IconButton({ onChange(rows.filterIndexed { j, _ -> j != i }.ifEmpty { listOf("") }) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.contacts_remove_line))
            }
        }
    }
    TextButton({ onChange(rows + "") }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(add), Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun BookPicker(books: List<BookChoice>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
        Text("${stringResource(R.string.contacts_address_book)}: ${books.firstOrNull { it.id == selected }?.name.orEmpty()}")
    }
    DropdownMenu(open, { open = false }) {
        books.forEach { b -> DropdownMenuItem({ Text(b.name) }, onClick = { onPick(b.id); open = false }) }
    }
}

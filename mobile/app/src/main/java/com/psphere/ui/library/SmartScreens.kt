package com.psphere.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.node.LibraryItem
import com.psphere.core.node.SmartAnswer
import com.psphere.core.node.SmartItem
import com.psphere.core.node.SmartOps
import com.psphere.ui.components.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asks the smart features (on the computer) and keeps the answer; null while asking. */
@Composable
internal fun smartAnswer(op: String, args: List<String>, refresh: Int = 0): SmartAnswer? {
    val engine by Engine.state.collectAsState()
    return produceState<SmartAnswer?>(null, op, args, refresh, engine.nearby.keys) {
        value = withContext(Dispatchers.IO) { Engine.node.smartAsk(op, args, Engine.onlineLinked()) }
    }.value
}

@Composable
internal fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/** Shown when no computer with smart photos can be reached and nothing was kept from before. */
@Composable
internal fun SmartOff() {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Text("Smart photos runs on your computer", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Turn it on in PSphere's Settings on your computer. Then search, people and places work here too, while the computer is on and on this Wi-Fi. People and places you've seen stay here for when you're away.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A small note when the answer is what this phone kept from last time. */
@Composable
internal fun SavedNote(answer: SmartAnswer) {
    if (answer.saved) Text(
        "Your computer isn't reachable, so this is from last time.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Search's starting page: people, places and things. */
@Composable
internal fun Explore(onPerson: (SmartItem) -> Unit, onPlace: (SmartItem) -> Unit, onThing: (SmartItem) -> Unit) {
    val answer = smartAnswer(SmartOps.EXPLORE, emptyList()) ?: return Loading()
    if (answer.from.isEmpty() && !answer.saved) return SmartOff()
    val people = answer.items.filter { it.kind == SmartItem.Kind.PERSON }
    val places = answer.items.filter { it.kind == SmartItem.Kind.PLACE }
    val things = answer.items.filter { it.kind == SmartItem.Kind.THING }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SavedNote(answer) }
        if (people.isEmpty() && places.isEmpty() && things.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Nothing yet. People show up once they're in a couple of photos, and places come from photos with a location. Your computer may still be looking through your photos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
            )
        }
        if (people.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading("People") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(people, key = { it.id }) { p -> PersonBubble(p) { onPerson(p) } }
                }
            }
        }
        if (places.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading("Places") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(places, key = { it.id }) { pl -> Box(Modifier.width(130.dp)) { CoverTile(pl.cover, pl.title) { onPlace(pl) } } }
                }
            }
        }
        if (things.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading("Things") }
            items(things, key = { it.id }) { t -> CoverTile(t.cover, t.title) { onThing(t) } }
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))

@Composable
internal fun Avatar(item: SmartItem, size: Dp) {
    val bmp = remember(item.image) { item.image?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonBubble(p: SmartItem, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(p, 72.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            p.title.ifEmpty { "Add a name" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = if (p.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A square photo with a label over its bottom edge (places and things). */
@Composable
private fun CoverTile(hash: String, title: String, onClick: () -> Unit) {
    val file = remember(hash) { Engine.node.previews.file(hash) }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        if (file != null) AsyncImage(file, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.5f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))))
        Text(title, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

/** Photos from a smart answer: a search, a place, a thing or a person, with an optional header. */
@Composable
internal fun SmartPhotos(
    op: String, args: List<String>, columns: Int, refresh: Int = 0,
    header: (@Composable () -> Unit)? = null,
    onOpen: (List<LibraryItem>, Int) -> Unit,
) {
    val answer = smartAnswer(op, args, refresh) ?: return Loading()
    val photos = remember(answer) { Engine.node.photosOf(answer.items) }
    if (answer.from.isEmpty() && !answer.saved) return SmartOff()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        item(span = { GridItemSpan(maxLineSpan) }) { SavedNote(answer) }
        if (photos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (op == SmartOps.SEARCH) "No matches. Try other words: what's in the photo, a person's name, a place, or words written in it." else "No photos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        } else if (op == SmartOps.SEARCH) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("${plural(photos.size, "photo")} · best matches first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        items(photos, key = { it.item.origin + "/" + it.item.key }) { li -> PhotoCell(li, small = columns > 4) { onOpen(photos, photos.indexOf(li)) } }
    }
}

/** The header of a person's page: their face, name (tap to name them) and how many photos. */
@Composable
internal fun PersonHeader(person: SmartItem, onName: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(person, 64.dp)
        Column {
            Text(person.title.ifEmpty { "Someone" }, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onName, contentPadding = PaddingValues(0.dp)) { Text(if (person.title.isEmpty()) "Add a name" else "Rename") }
        }
    }
}

@Composable
internal fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current.isEmpty()) "Who is this?" else "Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it.take(40) }, singleLine = true, label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text("If you use a name you've already given someone, the two are joined.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

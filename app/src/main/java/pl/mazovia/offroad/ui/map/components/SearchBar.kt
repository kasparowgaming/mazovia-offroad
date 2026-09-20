package pl.mazovia.offroad.ui.map.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.mazovia.offroad.domain.model.PlaceSearchResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight

@Composable
fun SearchBar(
    query: String,
    destinationName: String?,
    searchResults: List<PlaceSearchResult> = emptyList(),
    isSearching: Boolean = false,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onResultSelected: (PlaceSearchResult) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            TextField(
                value = destinationName ?: query,
                onValueChange = {
                    if (destinationName != null) {
                        onClear()
                        onQueryChange(it)
                    } else {
                        onQueryChange(it)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Szukaj celu lub przytrzymaj mapę") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Szukaj")
                },
                trailingIcon = {
                    if (destinationName != null || query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Close, contentDescription = "Wyczyść")
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        if (searchResults.isNotEmpty() && destinationName == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(searchResults) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultSelected(result) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(text = result.name, fontWeight = FontWeight.Bold)
                            val locality = result.localityContext
                            if (locality != null) {
                                Text(
                                    text = locality,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val distance = result.distanceMeters
                            if (distance != null) {
                                val km = distance / 1000.0
                                Text(
                                    text = String.format("%.1f km", km),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (result != searchResults.last()) {
                            Divider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

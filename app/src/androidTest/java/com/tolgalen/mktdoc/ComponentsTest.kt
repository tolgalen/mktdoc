package com.tolgalen.mktdoc

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tolgalen.mktdoc.data.OutlineNode
import com.tolgalen.mktdoc.ui.ActionGrid
import com.tolgalen.mktdoc.ui.OutlinePanel
import com.tolgalen.mktdoc.ui.Tile
import com.tolgalen.mktdoc.ui.radioRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun actionGridClicksEnabledTilesOnly() {
        var clicked = 0
        rule.setContent {
            MaterialTheme {
                ActionGrid(listOf(
                    Tile("Open", Icons.Default.Download, { clicked++ }),
                    Tile("Blocked", Icons.Default.Download, { clicked += 100 }, enabled = false),
                ))
            }
        }
        rule.onNodeWithText("Open").assertIsEnabled().performClick()
        rule.onNodeWithText("Blocked").assertIsNotEnabled().performClick()
        assertEquals(1, clicked)
    }

    @Test fun radioRowIsOneSelectableElement() {
        rule.setContent {
            MaterialTheme {
                var sel by remember { mutableStateOf("a") }
                Row(Modifier.radioRow(sel == "a") { sel = "a" }) { RadioButton(sel == "a", null); Text("Alpha") }
            }
        }
        rule.onNodeWithText("Alpha").assertIsSelected()
    }

    @Test fun outlinePanelExpandsAndOpensPages() {
        var opened: String? = null
        val outline = listOf(
            OutlineNode("Getting Started", "getting-started.html", listOf(OutlineNode("First steps", "getting-started/first.html", emptyList()))),
            OutlineNode("Hardware", null, listOf(OutlineNode("Routerboard", "hardware/rb.html", emptyList()))),
        )
        rule.setContent { MaterialTheme { OutlinePanel(outline, null, { opened = it }) } }
        rule.onNodeWithText("First steps").assertDoesNotExist()
        // a section without a page just expands
        rule.onNodeWithText("Hardware").performClick()
        rule.onNodeWithText("Routerboard").performClick()
        assertEquals("hardware/rb.html", opened)
        // a section with a page opens it and expands
        rule.onNodeWithText("Getting Started").performClick()
        assertEquals("getting-started.html", opened)
        rule.onNodeWithText("First steps").assertExists()
    }

    @Test fun outlinePanelRevealsTheCurrentPage() {
        val outline = listOf(OutlineNode("Hardware", null, listOf(OutlineNode("Routerboard", "hardware/rb.html", emptyList()))))
        rule.setContent { MaterialTheme { OutlinePanel(outline, "hardware/rb.html", {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Routerboard").assertExists()
    }
}

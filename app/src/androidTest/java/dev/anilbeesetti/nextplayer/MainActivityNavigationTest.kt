package dev.anilbeesetti.nextplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.anilbeesetti.nextplayer.core.common.storagePermission
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.navigation.TopLevelDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(storagePermission)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsOpensAfterActivityRecreation() {
        recreateHome()
        val appearance = composeRule.activity.getString(R.string.appearance_name)

        composeRule.onNodeWithTag(settingsTabTag).performClick()

        composeRule.onNodeWithText(appearance).assertIsDisplayed()
    }

    @Test
    fun vaultOpensAfterActivityRecreation() {
        recreateHome()
        val vault = composeRule.activity.getString(R.string.vault)
        val enterPin = composeRule.activity.getString(R.string.enter_vault_pin)
        val setPin = composeRule.activity.getString(R.string.set_vault_pin)

        composeRule.onNodeWithTag(settingsTabTag).performClick()
        composeRule.onNodeWithText(vault).performScrollTo().performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(enterPin).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText(setPin).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun recreateHome() {
        val settings = composeRule.activity.getString(R.string.settings)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(settings).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(settings).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private val settingsTabTag: String
        get() = "top_level_tab_${TopLevelDestination.SETTINGS.name}"
}

package com.custom.keyboard.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HubStoreTest {
    @Test
    fun sortsNotificationsIntoHubKinds() {
        assertEquals(HubStore.Kind.EMAIL, HubStore.kindOf("email", false, null))
        assertEquals(HubStore.Kind.CALL, HubStore.kindOf("missed_call", false, null))
        assertEquals(HubStore.Kind.MESSAGE, HubStore.kindOf(null, true, null))
        assertEquals(HubStore.Kind.MESSAGE, HubStore.kindOf(null, false, AppCategories.SOCIAL))
        assertEquals(HubStore.Kind.SOCIAL, HubStore.kindOf("social", false, null))
        assertEquals(HubStore.Kind.OTHER, HubStore.kindOf("promo", false, null))
    }
}

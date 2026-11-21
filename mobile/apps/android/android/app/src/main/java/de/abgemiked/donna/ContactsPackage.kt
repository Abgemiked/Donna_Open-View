package com.yourcompany.donna

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class ContactsPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext) = listOf(ContactsModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

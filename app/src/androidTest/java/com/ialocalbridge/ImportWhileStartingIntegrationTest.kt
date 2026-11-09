package com.ialocalbridge

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportWhileStartingIntegrationTest {
 @Test fun pendingImportCannotReplaceProviderAfterServerStarts() {
  val instrumentation=InstrumentationRegistry.getInstrumentation(); val ctx=instrumentation.targetContext
  val repo=ProviderRepository(ctx); val original=repo.getAll(); assertTrue(original.isNotEmpty())
  val candidate=File(ctx.cacheDir,"import-race-fixture.json")
  candidate.writeText(Gson().toJson(original.first().copy(provider="RACE_FIXTURE")))
  val intent=Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  val activity=instrumentation.startActivitySync(intent) as MainActivity
  val starting=MainActivity::class.java.getDeclaredField("serverStarting").apply { isAccessible=true }
  val import=MainActivity::class.java.getDeclaredMethod("importProvider",Uri::class.java).apply { isAccessible=true }
  try {
   instrumentation.runOnMainSync { starting.setBoolean(activity,false); import.invoke(activity,Uri.fromFile(candidate)) }
   onView(withText("Enregistrer")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
   instrumentation.runOnMainSync { starting.setBoolean(activity,true) }
   onView(withText("Enregistrer")).perform(click())
   instrumentation.waitForIdleSync()
   assertEquals(original.map { it.provider },repo.getAll().map { it.provider })
   assertNull(repo.get("RACE_FIXTURE"))
  } finally {
   instrumentation.runOnMainSync { starting.setBoolean(activity,false); activity.finish() }
   candidate.delete()
  }
 }
}

package com.lastwave.app.di

import android.content.Context
import com.lastwave.app.data.update.ApkArchiveInspector
import com.lastwave.app.data.update.PackageManagerApkArchiveInspector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The updater reads a downloaded APK through the platform package manager,
     * which is the one part of its verification that cannot be asserted on a JVM.
     * Behind this interface the manager's decisions are unit-tested with a fake,
     * while the device still gets the real [android.content.pm.PackageManager]
     * answers — including "this OEM build will not tell me the signer", which is a
     * different UI caveat from "the signer differs".
     */
    @Provides
    @Singleton
    fun provideApkArchiveInspector(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): ApkArchiveInspector =
        PackageManagerApkArchiveInspector(context)
}

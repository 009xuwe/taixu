package top.wkbin.taixu.di.core.common

import org.koin.dsl.module
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.CrashReporter
import top.wkbin.taixu.core.common.navigation.GlobalNavigationBus

/** Dependency registrations owned by the core:common module. */
val coreCommonModule = module {
    single<AppLogger> { AppLogger(context = get(), secretRedactor = get()) }

    single<CrashReporter> { CrashReporter(context = get(), secretRedactor = get()) }

    single<GlobalNavigationBus> { GlobalNavigationBus() }

    single<top.wkbin.taixu.core.common.translation.TranslationManager> {
        top.wkbin.taixu.core.common.translation.TranslationManager(context = get(), appLogger = get())
    }
}

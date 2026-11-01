package com.zflgm.player.core.media

import com.zflgm.player.core.common.di.DispatchersModule
import com.zflgm.player.core.database.DatabaseModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [DatabaseModule::class, DispatchersModule::class])
@ComponentScan
class MediaModule

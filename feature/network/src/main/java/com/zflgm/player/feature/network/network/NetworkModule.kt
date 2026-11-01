package com.zflgm.player.feature.network

import com.zflgm.player.core.data.DataModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [DataModule::class])
@ComponentScan
class NetworkModule

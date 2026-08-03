package com.dhaval.echo.di

import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.db.UserDao
import com.dhaval.echo.data.user.FirestoreUserRepository
import com.dhaval.echo.data.user.RealUserRepository
import com.dhaval.echo.domain.user.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UserModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FirestoreUserRepository): UserRepository

    companion object {
        @Provides
        @Singleton
        fun provideUserDao(db: EchoDatabase): UserDao = db.userDao()
    }
}

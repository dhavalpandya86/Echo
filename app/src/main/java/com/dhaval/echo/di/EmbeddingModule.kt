package com.dhaval.echo.di

import com.dhaval.echo.data.embeddings.OnDeviceEmbeddingEngine
import com.dhaval.echo.data.embeddings.OnnxTextTokenizer
import com.dhaval.echo.domain.embeddings.EmbeddingEngine
import com.dhaval.echo.domain.embeddings.TextTokenizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddingModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingEngine(impl: OnDeviceEmbeddingEngine): EmbeddingEngine

    /**
     * Real XLM-RoBERTa tokenizer (AI-LOCAL-03), replacing the placeholder that
     * refused to encode. Verified to match HF's reference tokenizer exactly.
     */
    @Binds
    @Singleton
    abstract fun bindTextTokenizer(impl: OnnxTextTokenizer): TextTokenizer
}

// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "BlockingMethodInNonBlockingContext")
package org.jetbrains.intellij.build.impl.compilation

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.cio.use
import io.ktor.utils.io.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.*
import okio.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal val READ_OPERATION = EnumSet.of(StandardOpenOption.READ)
internal const val MAX_BUFFER_SIZE = 4_000_000

internal suspend fun uploadArchives(
  config: CompilationCacheUploadConfiguration,
  items: List<PackAndUploadItem>,
  bufferPool: DirectFixedSizeByteBufferPool,
  client: HttpClient,
) {
  val uploadedCount = AtomicInteger()
  val uploadedBytes = AtomicLong()
  val reusedCount = AtomicInteger()
  val reusedBytes = AtomicLong()

  val alreadyUploaded = emptySet<String>()
  coroutineScope {
    for (item in items) {
      if (alreadyUploaded.contains(item.name)) {
        reusedCount.getAndIncrement()
        reusedBytes.getAndAdd(Files.size(item.archive))
        continue
      }

      launch {
        val isUploaded = uploadFile(
          url = "${config.serverUrl}/$uploadPrefix/${item.name}/${item.hash!!}.jar",
          file = item.archive,
          bufferPool = bufferPool,
          client = client,
        )

        val size = Files.size(item.archive)
        if (isUploaded) {
          uploadedCount.getAndIncrement()
          uploadedBytes.getAndAdd(size)
        } else {
          reusedCount.getAndIncrement()
          reusedBytes.getAndAdd(size)
        }
      }
    }
  }
}

// Using ZSTD dictionary doesn't make the difference, even slightly worse (default compression level 3).
// That's because in our case we compress a relatively large archive of class files.
private suspend fun uploadFile(
  url: String,
  file: Path,
  bufferPool: DirectFixedSizeByteBufferPool,
  client: HttpClient,
): Boolean {
  val fileSize = Files.size(file)
  if (Zstd.compressBound(fileSize) <= MAX_BUFFER_SIZE) {
    compressSmallFile(file = file, fileSize = fileSize, bufferPool = bufferPool, url = url, client = client)
  } else {
    val response = client.put(url) {
      setBody(object : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType
          get() = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
          channel.use {
            compressFile(file = file, output = channel, bufferPool = bufferPool)
          }
        }
      })
    }
    response.bodyAsChannel().discard()
  }

  return true
}

private suspend fun compressSmallFile(file: Path, fileSize: Long, bufferPool: DirectFixedSizeByteBufferPool, url: String, client: HttpClient) {
  val targetBuffer = bufferPool.allocate()
  try {
    var readOffset = 0L
    val sourceBuffer = bufferPool.allocate()
    var isSourceBufferReleased = false
    try {
      FileChannel.open(file, READ_OPERATION).use { input ->
        do {
          readOffset += input.read(sourceBuffer, readOffset)
        } while (readOffset < fileSize)
      }
      sourceBuffer.flip()

      Zstd.compress(targetBuffer, sourceBuffer, 3, false)
      targetBuffer.flip()

      bufferPool.release(sourceBuffer)
      isSourceBufferReleased = true
    } finally {
      if (!isSourceBufferReleased) {
        bufferPool.release(sourceBuffer)
      }
    }

    val compressedSize = targetBuffer.remaining()

    val response = client.put(url) {
      setBody(object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long
          get() = compressedSize.toLong()

        override val contentType: ContentType
          get() = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
          targetBuffer.mark()
          channel.use {
            channel.writeFully(targetBuffer)
          }
          targetBuffer.reset()
        }
      })
    }

    response.bodyAsChannel().discard()
  } finally {
    bufferPool.release(targetBuffer)
  }
}

private suspend fun compressFile(file: Path, output: ByteWriteChannel, bufferPool: DirectFixedSizeByteBufferPool) {
  // write is done by one client, and read is performed in parallel, but that's ok as the list is immutable
  var pendingData = persistentListOf<ByteBuffer>()
  // zstd on close can call flushBuffer, but we don't want to allocate a new buffer because it will be not be used
  var isDone = false
  object : ZstdDirectBufferCompressingStreamNoFinalizer(bufferPool.allocate(), 3) {
    override fun flushBuffer(toFlush: ByteBuffer): ByteBuffer {
      toFlush.flip()

      // in most cases will be the only element in the list,
      // two will be if source data is not compressible and the target buffer is not enough
      pendingData = pendingData.add(toFlush)
      return if (isDone) toFlush else bufferPool.allocate()
    }

    override fun close() {
      try {
        super.close()
      } finally {
        // yes, `target` buffer will be not released if flushBuffer was not yet called (as will be not added to pendingData), that's ok.
        if (!isDone) {
          pendingData.forEach(bufferPool::release)
          pendingData = persistentListOf()
        }
      }
    }
  }.use { compressor ->
    var flushJob: Job? = null

    val sourceBuffer = bufferPool.allocate()
    try {
      coroutineScope {
        var offset = 0L
        FileChannel.open(file, READ_OPERATION).use { input ->
          val fileSize = input.size()
          while (offset < fileSize && isActive) {
            val actualBlockSize = (fileSize - offset).toInt()
            if (sourceBuffer.remaining() > actualBlockSize) {
              sourceBuffer.limit(sourceBuffer.position() + actualBlockSize)
            }

            var readOffset = offset
            do {
              readOffset += input.read(sourceBuffer, readOffset)
            } while (sourceBuffer.hasRemaining())

            sourceBuffer.flip()
            compressor.compress(sourceBuffer)

            // assume that writing to remote may be finished while compressing another next data
            flushJob?.join()
            // Yes, uploading is done in parallel, so, in general no need to perform write in another thread
            // because while waiting for write data to remote, another thread will do the compression or writing for another file.
            // But better if client request will be done as fast as possible,
            // because nginx writes data to a temp file / there are some timeouts / client connection limits and so on.
            flushJob = scheduleWrite(pendingData, output, bufferPool)
            pendingData = persistentListOf()

            sourceBuffer.clear()
            offset = readOffset
          }
        }
      }
    } finally {
      bufferPool.release(sourceBuffer)
    }

    flushJob?.join()
    flushJob = null

    isDone = true
    compressor.close()
  }

  // compressor.close can flush data, so, write pendingData if any
  writePendingData(pendingData, output, bufferPool)
}

private fun CoroutineScope.scheduleWrite(
  pendingData: PersistentList<ByteBuffer>,
  output: ByteWriteChannel,
  bufferPool: DirectFixedSizeByteBufferPool
): Job? {
  if (pendingData.isEmpty()) {
    return null
  }

  return launch {
    writePendingData(pendingData, output, bufferPool)
  }
}

private suspend fun writePendingData(
  pendingData: PersistentList<ByteBuffer>,
  output: ByteWriteChannel,
  bufferPool: DirectFixedSizeByteBufferPool
) {
  for (buffer in pendingData) {
    output.writeFully(buffer)
    bufferPool.release(buffer)
  }
}
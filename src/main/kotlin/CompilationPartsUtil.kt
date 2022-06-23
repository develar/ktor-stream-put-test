// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "UnstableApiUsage")

package org.jetbrains.intellij.build.impl.compilation

import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.client.engine.okhttp.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) {
  val zipDir = Path.of("out/compilation-archive")
  val items = Json.decodeFromString<CompilationPartsMetadata>(Files.readString(zipDir.resolve("metadata.json"))).files.map {
    val item = PackAndUploadItem(output = Path.of(""), name = it.key, archive = zipDir.resolve("${it.key}.jar"))
    item.hash = it.value
    item
  }

  val engineName = args.firstOrNull() ?: "apache"
  println(engineName)
  val client = when (engineName) {
    "apache" -> HttpClient(Apache) {
      expectSuccess = true
    }
    "okhttp" -> HttpClient(OkHttp) {
      expectSuccess = true
    }
    else -> throw IllegalStateException("Unknown engine")
  }

  upload(items, client)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun upload(items: List<PackAndUploadItem>, client: HttpClient) {
  deleteDirectory(Path.of("/tmp/nginx-data-ktor-test"))

  val parallelism = ForkJoinPool.getCommonPoolParallelism()
  createBufferPool().use { bufferPool ->
    val duration = Duration.ofMillis(measureTimeMillis {
      println("uploading...")
      runBlocking(Dispatchers.IO.limitedParallelism(parallelism)) {
        uploadArchives(
          config = CompilationCacheUploadConfiguration(
            serverUrl = "http://localhost:8010",
            branch = "master",
          ),
          items = items,
          bufferPool = bufferPool,
          client = client,
        )
      }
    })
    println("${duration.seconds} s ${duration.toMillisPart()} ms (parallelism=$parallelism)")
    exitProcess(0)
  }
}

private fun deleteDirectory(directory: Path) {
  println("delete $directory")
  if (Files.exists(directory)) {
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
      override fun visitFile(path: Path, attr: BasicFileAttributes): FileVisitResult {
        Files.delete(path)
        return FileVisitResult.CONTINUE
      }

      override fun postVisitDirectory(path: Path, ex: IOException?): FileVisitResult {
        Files.delete(path)
        return FileVisitResult.CONTINUE
      }
    })
  }
}

class CompilationCacheUploadConfiguration(
  serverUrl: String? = null,
  branch: String? = null,
) {
  val serverUrl: String = serverUrl ?: normalizeServerUrl()
  val branch: String = branch ?: System.getProperty(branchPropertyName).also {
    check(!it.isNullOrBlank()) {
      "Git branch is not defined. Please set $branchPropertyName system property."
    }
  }

  companion object {
    private const val branchPropertyName = "intellij.build.compiled.classes.branch"

    private fun normalizeServerUrl(): String {
      val serverUrlPropertyName = "intellij.build.compiled.classes.server.url"
      var result = System.getProperty(serverUrlPropertyName)?.trimEnd('/')
      check(!result.isNullOrBlank()) {
        "Compilation cache archive server url is not defined. Please set $serverUrlPropertyName system property."
      }
      if (!result.startsWith("http")) {
        @Suppress("HttpUrlsUsage")
        result = (if (result.startsWith("localhost:")) "http://" else "https://") + result
      }
      return result
    }
  }
}

private fun createBufferPool(): DirectFixedSizeByteBufferPool {
  // 4MB block, x2 of FJP thread count - one buffer to source, another one for target
  return DirectFixedSizeByteBufferPool(size = MAX_BUFFER_SIZE, maxPoolSize = ForkJoinPool.getCommonPoolParallelism() * 2)
}

// TODO: Remove hardcoded constant
internal const val uploadPrefix = "intellij-compile/v2"

private val sharedDigest = MessageDigest.getInstance("SHA-256", java.security.Security.getProvider("SUN"))
internal fun sha256() = sharedDigest.clone() as MessageDigest

internal data class PackAndUploadItem(
  val output: Path,
  val name: String,
  val archive: Path,
) {
  var hash: String? = null
}

/**
 * Configuration on which compilation parts to download and from where.
 * <br/>
 * URL for each part should be constructed like: <pre>${serverUrl}/${prefix}/${files.key}/${files.value}.jar</pre>
 */
@Serializable
private data class CompilationPartsMetadata(
  @SerialName("server-url") val serverUrl: String,
  val branch: String,
  val prefix: String,
  /**
   * Map compilation part path to a hash, for now SHA-256 is used.
   * sha256(file) == hash, though that may be changed in the future.
   */
  val files: Map<String, String>,
)
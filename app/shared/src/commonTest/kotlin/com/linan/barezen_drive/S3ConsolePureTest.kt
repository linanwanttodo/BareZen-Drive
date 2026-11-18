package com.linan.barezen_drive

import com.linan.barezen_drive.data.s3.BucketNameProblem
import com.linan.barezen_drive.data.s3.S3Client
import com.linan.barezen_drive.data.s3.buildClientConfig
import com.linan.barezen_drive.data.s3.s3BucketNameOk
import com.linan.barezen_drive.data.s3.s3BucketNameProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ConsolePureTest {

    @Test
    fun validBucketNames() {
        assertTrue(s3BucketNameOk("photos"))
        assertTrue(s3BucketNameOk("my-bucket"))
        assertTrue(s3BucketNameOk("a.b.c"))
        assertTrue(s3BucketNameOk("abc"))
        assertTrue(s3BucketNameOk("123"))
        assertTrue(s3BucketNameOk("a".repeat(63)))
    }

    @Test
    fun invalidBucketNames() {
        assertFalse(s3BucketNameOk("ab"))
        assertFalse(s3BucketNameOk("a".repeat(64)))
        assertFalse(s3BucketNameOk("UPPER"))
        assertFalse(s3BucketNameOk("has space"))
        assertFalse(s3BucketNameOk("中文"))
        assertFalse(s3BucketNameOk(".leading"))
        assertFalse(s3BucketNameOk("trailing-"))
        assertFalse(s3BucketNameOk("double..dot"))
        assertFalse(s3BucketNameOk("192.168.1.1"))
        assertFalse(s3BucketNameOk(""))
    }

    @Test
    fun problemDiagnosis() {
        assertEquals(BucketNameProblem.TOO_SHORT, s3BucketNameProblem("ab"))
        assertEquals(BucketNameProblem.TOO_LONG, s3BucketNameProblem("a".repeat(64)))
        assertEquals(BucketNameProblem.UPPERCASE, s3BucketNameProblem("Photos"))
        assertEquals(BucketNameProblem.INVALID_CHARS, s3BucketNameProblem("我的文档"))
        assertEquals(BucketNameProblem.BAD_EDGE, s3BucketNameProblem("-lead"))
        assertEquals(BucketNameProblem.DOUBLE_DOT, s3BucketNameProblem("a..b"))
        assertEquals(BucketNameProblem.IP_SHAPE, s3BucketNameProblem("10.0.0.1"))
        assertNull(s3BucketNameProblem("valid-name"))
    }

    @Test
    fun rcloneConfigContainsEndpointAndKey() {
        val cfg = buildClientConfig(S3Client.RCLONE, "https://drive.example.com", "key-123")
        assertTrue(cfg.contains("endpoint = https://drive.example.com"))
        assertTrue(cfg.contains("access_key_id = key-123"))
        assertTrue(cfg.contains("force_path_style = true"))
        assertTrue(cfg.contains("<Secret Key>"))
    }

    @Test
    fun endpointTrailingSlashIsNormalized() {
        val cfg = buildClientConfig(S3Client.RCLONE, "https://drive.example.com/", "k")
        assertTrue(cfg.contains("endpoint = https://drive.example.com\n"))
    }

    @Test
    fun awsCliUsesBucketInCopyExample() {
        val cfg = buildClientConfig(S3Client.AWS_CLI, "https://d.co", "ak", bucket = "photos")
        assertTrue(cfg.contains("s3://photos/file.bin"))
        assertTrue(cfg.contains("--endpoint-url https://d.co"))
    }

    @Test
    fun mcAliasQuotesCredentials() {
        val cfg = buildClientConfig(S3Client.MC, "https://d.co", "ak")
        assertTrue(cfg.contains("mc alias set barezen https://d.co 'ak' '<Secret Key>' --api S3v4"))
    }

    @Test
    fun resticForcesPathStyle() {
        val cfg = buildClientConfig(S3Client.RESTIC, "https://d.co", "ak", bucket = "backup")
        assertTrue(cfg.contains("s3.force-path-style=true"))
        assertTrue(cfg.contains("s3:https://d.co/backup"))
    }

    @Test
    fun windowsShowsWebdavMount() {
        val cfg = buildClientConfig(S3Client.WINDOWS, "https://d.co", "ak")
        assertTrue(cfg.contains("https://d.co/dav"))
        assertTrue(cfg.contains("net use Z:"))
    }

    @Test
    fun macosShowsFinderConnect() {
        val cfg = buildClientConfig(S3Client.MACOS, "https://d.co", "ak")
        assertTrue(cfg.contains("Connect to Server"))
        assertTrue(cfg.contains("https://d.co/dav"))
    }

    @Test
    fun cyberduckStripsSchemeForServerField() {
        val cfg = buildClientConfig(S3Client.CYBERDUCK, "https://d.co", "ak")
        assertTrue(cfg.contains("Server: d.co"))
        assertTrue(cfg.contains("Port: 443"))
        assertTrue(cfg.contains("Access Key: ak"))
    }

    @Test
    fun noClientEmbedsARealSecret() {
        for (client in S3Client.entries) {
            val cfg = buildClientConfig(client, "https://d.co", "ak")
            assertTrue(cfg.contains("<Secret Key>") || cfg.contains("<App Password>"),
                "$client must use a placeholder, never a real secret")
        }
    }
}
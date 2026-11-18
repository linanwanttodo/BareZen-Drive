package com.linan.barezen_drive.data.s3

/**
 * S3 bucket-name dialect, mirrored from the server's S3Names.s3BucketNameOk.
 * The server remains the authority; this copy lets the create-bucket dialog
 * validate locally before a round trip and lets the console explain why an
 * existing folder is not a bucket.
 */
fun s3BucketNameOk(name: String): Boolean {
    if (name.length !in 3..63) return false
    if (!name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
    if (!name.first().isLetterOrDigit() || !name.last().isLetterOrDigit()) return false
    if (".." in name) return false
    if (Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(name)) return false
    return true
}

/** Why a folder name fails the S3 bucket dialect, for the console's hint line. */
fun s3BucketNameProblem(name: String): BucketNameProblem? {
    if (name.length < 3) return BucketNameProblem.TOO_SHORT
    if (name.length > 63) return BucketNameProblem.TOO_LONG
    if (name.any { it.isUpperCase() }) return BucketNameProblem.UPPERCASE
    if (name.any { !it.isAsciiBucketChar() }) return BucketNameProblem.INVALID_CHARS
    if (!name.first().isLetterOrDigit() || !name.last().isLetterOrDigit()) return BucketNameProblem.BAD_EDGE
    if (".." in name) return BucketNameProblem.DOUBLE_DOT
    if (Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(name)) return BucketNameProblem.IP_SHAPE
    return null
}

private fun Char.isAsciiBucketChar(): Boolean =
    this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '-'

enum class BucketNameProblem { TOO_SHORT, TOO_LONG, UPPERCASE, INVALID_CHARS, BAD_EDGE, DOUBLE_DOT, IP_SHAPE }

/** Clients the console can generate a ready-to-paste configuration for. */
enum class S3Client(val label: String) {
    RCLONE("rclone"),
    AWS_CLI("AWS CLI"),
    MC("MinIO mc"),
    RESTIC("restic"),
    WINDOWS("Windows"),
    MACOS("macOS"),
    CYBERDUCK("Cyberduck"),
}

/**
 * Builds a copy-paste configuration snippet for [client] against [endpoint]
 * using [accessKey]. The secret is never embedded: it exists only at creation
 * time, so the snippet carries a placeholder the user replaces once.
 *
 * [bucket] is used by clients whose config addresses a specific bucket;
 * pass null for account-wide setups (rclone remote, mc alias, aws configure).
 */
fun buildClientConfig(
    client: S3Client,
    endpoint: String,
    accessKey: String,
    bucket: String? = null,
): String {
    val ep = endpoint.trimEnd('/')
    val b = bucket ?: "my-bucket"
    return when (client) {
        S3Client.RCLONE -> buildString {
            appendLine("[barezen]")
            appendLine("type = s3")
            appendLine("provider = Other")
            appendLine("endpoint = $ep")
            appendLine("region = us-east-1")
            appendLine("access_key_id = $accessKey")
            appendLine("secret_access_key = <Secret Key>")
            appendLine("force_path_style = true")
            appendLine("no_check_bucket = true")
        }
        S3Client.AWS_CLI -> buildString {
            appendLine("aws configure set aws_access_key_id $accessKey")
            appendLine("aws configure set aws_secret_access_key <Secret Key>")
            appendLine("aws configure set region us-east-1")
            appendLine("aws --endpoint-url $ep s3 ls")
            appendLine("aws --endpoint-url $ep s3 cp ./file.bin s3://$b/file.bin")
        }
        S3Client.MC -> buildString {
            appendLine("mc alias set barezen $ep '$accessKey' '<Secret Key>' --api S3v4")
            appendLine("mc ls barezen")
            appendLine("mc cp ./file.bin barezen/$b/file.bin")
        }
        S3Client.RESTIC -> buildString {
            appendLine("export AWS_ACCESS_KEY_ID=$accessKey")
            appendLine("export AWS_SECRET_ACCESS_KEY=<Secret Key>")
            appendLine("restic -o s3.force-path-style=true -r s3:$ep/$b init")
            appendLine("restic -o s3.force-path-style=true -r s3:$ep/$b backup ~/Documents")
        }
        S3Client.WINDOWS -> buildString {
            appendLine("WebDAV: $ep/dav")
            appendLine("Username: <account username>")
            appendLine("Password: <App Password>")
            appendLine()
            appendLine("net use Z: \"$ep/dav\" /user:<account username> <App Password>")
        }
        S3Client.MACOS -> buildString {
            appendLine("Finder > Go > Connect to Server")
            appendLine("$ep/dav")
            appendLine("Username: <account username>")
            appendLine("Password: <App Password>")
        }
        S3Client.CYBERDUCK -> buildString {
            appendLine("Protocol: S3 (Amazon Simple Storage Service)")
            appendLine("Server: ${ep.removePrefix("https://").removePrefix("http://")}")
            appendLine("Port: ${if (ep.startsWith("https")) 443 else 80}")
            appendLine("Access Key: $accessKey")
            appendLine("Secret Key: <Secret Key>")
            appendLine("Path style: enabled")
            appendLine("Region: us-east-1")
        }
    }.trimEnd()
}
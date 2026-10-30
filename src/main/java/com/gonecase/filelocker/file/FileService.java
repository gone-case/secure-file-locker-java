package com.gonecase.filelocker.file;

import com.gonecase.filelocker.config.LockerProperties;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Objects are stored as "{uuid}/{sanitized-name}". Clients only ever address files by UUID,
 * so user input never becomes an arbitrary S3 key (no path traversal or prefix probing).
 */
@Service
public class FileService {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final int MAX_NAME = 100;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final LockerProperties props;

    public FileService(S3Client s3, S3Presigner presigner, LockerProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    public FileInfo upload(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new FileErrors.BadRequest("file is empty");

        byte[] bytes = file.getBytes();
        String id = UUID.randomUUID().toString();
        String name = sanitize(file.getOriginalFilename());
        String sha256 = sha256(bytes);
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        s3.putObject(PutObjectRequest.builder()
                        .bucket(props.bucket())
                        .key(id + "/" + name)
                        .contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)  // encrypted at rest with KMS
                        .ssekmsKeyId(props.kmsKeyId())
                        .metadata(Map.of("sha256", sha256))
                        .build(),
                RequestBody.fromBytes(bytes));

        return new FileInfo(id, name, bytes.length, Instant.now(), sha256);
    }

    public List<FileInfo> list() {
        List<FileInfo> out = new ArrayList<>();
        for (S3Object o : listAll(null)) out.add(toInfo(o));
        return out;
    }

    public DownloadLink downloadLink(String id) {
        S3Object obj = find(id);
        var get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(obj.key())
                .responseContentDisposition("attachment; filename=\"" + nameOf(obj.key()) + "\"")
                .build();
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(props.presignTtl())
                .getObjectRequest(get)
                .build());
        return new DownloadLink(presigned.url().toString(), presigned.expiration());
    }

    public void delete(String id) {
        S3Object obj = find(id);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(obj.key()).build());
    }

    // ---------------------------------------------------------------------------------------

    private S3Object find(String id) {
        validateId(id);
        return listAll(id + "/").stream().findFirst().orElseThrow(() -> new FileErrors.NotFound(id));
    }

    private List<S3Object> listAll(String prefix) {
        List<S3Object> all = new ArrayList<>();
        var req = ListObjectsV2Request.builder().bucket(props.bucket()).prefix(prefix).build();
        while (true) {
            ListObjectsV2Response res = s3.listObjectsV2(req);
            all.addAll(res.contents());
            if (!Boolean.TRUE.equals(res.isTruncated())) return all;
            req = req.toBuilder().continuationToken(res.nextContinuationToken()).build();
        }
    }

    static void validateId(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new FileErrors.BadRequest("invalid file id");
        }
    }

    static String sanitize(String original) {
        if (original == null || original.isBlank()) return "file";
        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);   // drop any client-supplied path
        base = UNSAFE.matcher(base).replaceAll("_");
        if (base.isBlank() || base.chars().allMatch(c -> c == '.')) return "file";
        return base.length() > MAX_NAME ? base.substring(base.length() - MAX_NAME) : base;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nameOf(String key) {
        return key.substring(key.indexOf('/') + 1);
    }

    private static FileInfo toInfo(S3Object o) {
        String id = o.key().substring(0, o.key().indexOf('/'));
        return new FileInfo(id, nameOf(o.key()), o.size(), o.lastModified(), null);
    }
}

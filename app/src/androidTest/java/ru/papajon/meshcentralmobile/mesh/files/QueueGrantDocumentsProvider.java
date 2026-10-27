package ru.papajon.meshcentralmobile.mesh.files;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Separate test-APK UID. Plain Java deliberately needs no target-APK Kotlin runtime. */
public final class QueueGrantDocumentsProvider extends DocumentsProvider {
    private File root() { File root = new File(getContext().getCacheDir(), "queue-grant-fixture"); root.mkdirs(); return root; }
    private File file(String id) {
        if (id.equals("root")) return root();
        if (!id.matches("[0-9a-f-]+")) throw new IllegalArgumentException("Invalid fixture id");
        return new File(root(), id);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i=0; i<columns.length; i++) switch (columns[i]) {
            case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: row[i] = "root"; break;
            case Root.COLUMN_TITLE: row[i] = "Queue grant fixture"; break;
            case Root.COLUMN_FLAGS: row[i] = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY; break;
            case Root.COLUMN_AVAILABLE_BYTES: row[i] = 100_000_000L; break;
        }
        cursor.addRow(row); return cursor;
    }
    private Cursor cursor(List<String> ids, String[] projection) throws FileNotFoundException {
        String[] columns = projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String id : ids) {
            File file = file(id); Object[] row = new Object[columns.length];
            for (int i=0; i<columns.length; i++) switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: row[i]=id; break;
                case Document.COLUMN_DISPLAY_NAME:
                    try { row[i] = id.equals("root") ? "Queue grant fixture" : new String(Files.readAllBytes(new File(root(), id + ".name").toPath()), StandardCharsets.UTF_8); }
                    catch (IOException e) { throw new FileNotFoundException("Missing fixture name"); } break;
                case Document.COLUMN_MIME_TYPE: row[i] = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                case Document.COLUMN_FLAGS: row[i] = Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | (file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE); break;
                case Document.COLUMN_SIZE: row[i] = file.length(); break;
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException { return cursor(Collections.singletonList(id), projection); }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("Invalid fixture parent");
        List<String> ids = new ArrayList<>(); File[] files = root().listFiles();
        if (files != null) for (File file : files) if (!file.getName().endsWith(".name")) ids.add(file.getName());
        return cursor(ids, projection);
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("Invalid fixture parent");
        String id = UUID.randomUUID().toString();
        try {
            if (mime.equals(Document.MIME_TYPE_DIR)) { if (!file(id).mkdir()) throw new IOException(); }
            else { if (!file(id).createNewFile()) throw new IOException(); }
            Files.write(new File(root(), id + ".name").toPath(), name.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { throw new FileNotFoundException("Fixture create failed"); }
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode)); }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        if (id.equals("root")) throw new FileNotFoundException("Cannot rename fixture root");
        try { Files.write(new File(root(), id + ".name").toPath(), name.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new FileNotFoundException("Fixture rename failed"); }
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.equals("root") || !file(id).delete()) throw new FileNotFoundException("Cannot delete fixture");
        new File(root(), id + ".name").delete();
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && !id.equals("root") && file(id).exists(); }
}

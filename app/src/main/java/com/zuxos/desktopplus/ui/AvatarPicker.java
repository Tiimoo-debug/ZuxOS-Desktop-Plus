package com.zuxos.desktopplus.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import com.zuxos.desktopplus.notify.NotifyProvider;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Picks the picture for the account bar on the desktop's app drawer.
 *
 * <p>No window of its own: it opens Android's photo picker, which needs no permission at all,
 * centre-crops what comes back to a 256px square, keeps it in the module's files and tells the
 * launcher - through the provider it already reads - that the picture has changed. The launcher
 * starts it on the screen the drawer was on.
 */
public final class AvatarPicker extends Activity {

    private static final int PICK = 1;
    private static final int SIZE = 256;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            return;
        }
        Intent pick;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(pick, PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "No photo picker on this device", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = data != null ? data.getData() : null;
        if (request == PICK && result == RESULT_OK && uri != null) {
            save(uri);
        }
        finish();
    }

    private void save(Uri uri) {
        try {
            Bitmap source = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, src) -> {
                        // Decoded small to begin with: a 50-megapixel photo is not needed for a
                        // 40dp circle.
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        int shortest = Math.max(1, Math.min(w, h));
                        float scale = Math.min(1f, SIZE * 2f / shortest);
                        decoder.setTargetSize(Math.max(1, Math.round(w * scale)),
                                Math.max(1, Math.round(h * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            int side = Math.min(source.getWidth(), source.getHeight());
            Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                    (source.getHeight() - side) / 2, side, side);
            Bitmap scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
            File file = new File(getFilesDir(), NotifyProvider.AVATAR_FILE);
            try (FileOutputStream out = new FileOutputStream(file)) {
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            getContentResolver().notifyChange(NotifyProvider.AVATAR, null);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not use that picture", Toast.LENGTH_SHORT).show();
        }
    }
}

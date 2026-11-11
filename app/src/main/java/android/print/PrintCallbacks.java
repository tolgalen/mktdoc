package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;

/**
 * The constructors of LayoutResultCallback and WriteResultCallback are not visible outside the
 * android.print package, so they are subclassed here to print a WebView to PDF without a print dialog.
 */
public final class PrintCallbacks {
    private PrintCallbacks() {}

    public interface LayoutListener {
        void onFinished();
        void onFailed(CharSequence error);
    }

    public interface WriteListener {
        void onFinished();
        void onFailed(CharSequence error);
    }

    public static void layout(PrintDocumentAdapter adapter, PrintAttributes attrs, CancellationSignal signal, final LayoutListener l) {
        adapter.onLayout(null, attrs, signal, new PrintDocumentAdapter.LayoutResultCallback() {
            @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) { l.onFinished(); }
            @Override public void onLayoutFailed(CharSequence error) { l.onFailed(error); }
        }, new Bundle());
    }

    public static void write(PrintDocumentAdapter adapter, ParcelFileDescriptor pfd, CancellationSignal signal, final WriteListener l) {
        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, signal, new PrintDocumentAdapter.WriteResultCallback() {
            @Override public void onWriteFinished(PageRange[] pages) { l.onFinished(); }
            @Override public void onWriteFailed(CharSequence error) { l.onFailed(error); }
        });
    }
}

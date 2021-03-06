package com.bumptech.glide.load.resource.gif;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.bumptech.glide.Glide;
import com.bumptech.glide.gifdecoder.GifDecoder;
import com.bumptech.glide.gifdecoder.GifHeader;
import com.bumptech.glide.gifdecoder.GifHeaderParser;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.UnitTransformation;
import com.bumptech.glide.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Queue;
import java.util.UUID;

/**
 * An {@link com.bumptech.glide.load.ResourceDecoder} that decodes
 * {@link com.bumptech.glide.load.resource.gif.GifDrawable} from {@link java.io.InputStream} data.
 */
public class GifResourceDecoder implements ResourceDecoder<InputStream, GifDrawable> {
    private static final String TAG = "GifResourceDecoder";
    private static final GifHeaderParserPool PARSER_POOL = new GifHeaderParserPool();
    private static final GifDecoderPool DECODER_POOL = new GifDecoderPool();

    private final Context context;
    private final GifHeaderParserPool parserPool;
    private final BitmapPool bitmapPool;
    private final GifDecoderPool decoderPool;
    private final GifBitmapProvider provider;

    public GifResourceDecoder(Context context) {
        this(context, Glide.get(context).getBitmapPool());
    }

    public GifResourceDecoder(Context context, BitmapPool bitmapPool) {
        this(context, bitmapPool, PARSER_POOL, DECODER_POOL);
    }

    // Visible for testing.
    GifResourceDecoder(Context context, BitmapPool bitmapPool, GifHeaderParserPool parserPool,
            GifDecoderPool decoderPool) {
        this.context = context;
        this.bitmapPool = bitmapPool;
        this.decoderPool = decoderPool;
        this.provider = new GifBitmapProvider(bitmapPool);
        this.parserPool = parserPool;
    }

    @Override
    public GifDrawableResource decode(InputStream source, int width, int height) {
        byte[] data = inputStreamToBytes(source);
        final GifHeaderParser parser = parserPool.obtain(data);
        final GifDecoder decoder = decoderPool.obtain(provider);
        try {
            return decode(data, width, height, parser, decoder);
        } finally {
            parserPool.release(parser);
            decoderPool.release(decoder);
        }
    }

    private GifDrawableResource decode(byte[] data, int width, int height, GifHeaderParser parser, GifDecoder decoder) {
        final GifHeader header = parser.parseHeader();
        if (header.getNumFrames() <= 0 || header.getStatus() != GifDecoder.STATUS_OK) {
            // If we couldn't decode the GIF, we will end up with a frame count of 0.
            return null;
        }

        String id = getGifId(data);
        Bitmap firstFrame = decodeFirstFrame(decoder, id, header, data);
        Transformation<Bitmap> unitTransformation = UnitTransformation.get();

        GifDrawable gifDrawable = new GifDrawable(context, provider, bitmapPool, unitTransformation, width, height, id,
                header, data, firstFrame);

        return new GifDrawableResource(gifDrawable);
    }

    private Bitmap decodeFirstFrame(GifDecoder decoder, String id, GifHeader header, byte[] data) {
        decoder.setData(id, header, data);
        decoder.advance();
        return decoder.getNextFrame();
    }

    @Override
    public String getId() {
        return "";
    }

    // A best effort attempt to get a unique id that can be used as a cache key for frames of the decoded GIF.
    private static String getGifId(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(data);
            return Util.sha1BytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Missing sha1 algorithm?", e);
            }
        }
        return UUID.randomUUID().toString();
    }

    private static byte[] inputStreamToBytes(InputStream is) {
        final int bufferSize = 16384;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bufferSize);
        try {
            int nRead;
            byte[] data = new byte[bufferSize];
            while ((nRead = is.read(data)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
        } catch (IOException e) {
            Log.w(TAG, "Error reading data from stream", e);
        }
        //TODO the returned byte[] may be partial if an IOException was thrown from read
        return buffer.toByteArray();
    }

    // Visible for testing.
    static class GifDecoderPool {
        private final Queue<GifDecoder> pool = Util.createQueue(0);

        public synchronized GifDecoder obtain(GifDecoder.BitmapProvider bitmapProvider) {
            GifDecoder result = pool.poll();
            if (result == null) {
                result = new GifDecoder(bitmapProvider);
            }
            return result;
        }

        public synchronized void release(GifDecoder decoder) {
            decoder.clear();
            pool.offer(decoder);
        }
    }

    // Visible for testing.
    static class GifHeaderParserPool {
        private final Queue<GifHeaderParser> pool = Util.createQueue(0);

        public synchronized GifHeaderParser obtain(byte[] data) {
            GifHeaderParser result = pool.poll();
            if (result == null) {
                result = new GifHeaderParser();
            }
            return result.setData(data);
        }

        public synchronized void release(GifHeaderParser parser) {
            pool.offer(parser);
        }
    }
}

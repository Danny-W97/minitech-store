package org.demo.customerservice.mfa;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Service
public class TotpQrCodeService {

    private static final int IMAGE_SIZE = 260;

    public String createPngDataUri(String otpauthUri) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    otpauthUri,
                    BarcodeFormat.QR_CODE,
                    IMAGE_SIZE,
                    IMAGE_SIZE,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 2
                    )
            );
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("Could not generate TOTP QR code", exception);
        }
    }
}

package org.demo.returnsservice.web;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException error) {
        String code=error.getReason()==null?"RETURN_REQUEST_REJECTED":error.getReason();
        String message=switch(code) {
            case "RETURN_DOMAIN_EFFECTS_NOT_RECONCILED" -> "Zatwierdzenie zgłoszenia nie potwierdza zwrotu pieniędzy ani zapasu. Wymagane odrębne rozliczenie skutków.";
            case "RETURN_VERSION_CONFLICT" -> "Zgłoszenie zmieniło się. Odczytaj bieżący stan przed kolejną decyzją.";
            case "RETURN_LEGACY_REVIEW_REQUIRED" -> "Starsze zgłoszenie wymaga weryfikacji zamówienia przez operatora.";
            case "RETURN_INTENT_CONFLICT" -> "Zgłoszenie tego typu już istnieje z inną treścią. Sprawdź zapisane zgłoszenie.";
            case "RETURN_ORDER_NOT_ELIGIBLE" -> "Zgłoszenie wymaga opłaconego zakupu we właściwym stanie realizacji.";
            default -> "Nie można potwierdzić żądanej operacji zgłoszenia.";
        };
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control","no-store").body(Map.of("status",error.getStatusCode().value(),"message",message,"details",Map.of("code",code)));
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class) ResponseEntity<?> validation() {
        return ResponseEntity.badRequest().body(Map.of("message","Wymagane prawidłowe dane, wersja i UUID operacji.","details",Map.of("code","RETURN_VALIDATION_FAILED")));
    }
    @ExceptionHandler(ArithmeticException.class) ResponseEntity<?> overflow() {
        return ResponseEntity.badRequest().body(Map.of("message","Ilość przekracza obsługiwany zakres.","details",Map.of("code","RETURN_QUANTITY_OVERFLOW")));
    }
}

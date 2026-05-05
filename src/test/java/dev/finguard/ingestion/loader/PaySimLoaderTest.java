package dev.finguard.ingestion.loader;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.ingestion.dto.PaySimRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PaySimLoader")
class PaySimLoaderTest {

    private PaySimLoader loader;

    @BeforeEach
    void setUp() {
        loader = new PaySimLoader();
    }

    // ==============================================================
    // parseRow() tests
    // ==============================================================

    @Nested
    @DisplayName("parseRow()")
    class ParseRowTests {

        @Test
        @DisplayName("Should parse a valid CSV row with all 11 columns")
        void parseRow_shouldParseValidRow() {
            String[] row = {"1", "TRANSFER", "50000.00", "C100", "100000.00",
                    "50000.00", "C200", "0.00", "50000.00", "0", "0"};

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isPresent();
            PaySimRow parsed = result.get();
            assertThat(parsed.step()).isEqualTo(1);
            assertThat(parsed.type()).isEqualTo("TRANSFER");
            assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(parsed.nameOrig()).isEqualTo("C100");
            assertThat(parsed.oldBalanceOrig()).isEqualByComparingTo(new BigDecimal("100000.00"));
            assertThat(parsed.newBalanceOrig()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(parsed.nameDest()).isEqualTo("C200");
            assertThat(parsed.isFraud()).isZero();
            assertThat(parsed.isFlaggedFraud()).isZero();
        }

        @Test
        @DisplayName("Should handle fraud flag values correctly")
        void parseRow_shouldParseFraudFlags() {
            String[] row = {"5", "CASH_OUT", "99999.00", "C102", "100000.00",
                    "1.00", "C300", "0.00", "99999.00", "1", "1"};

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isPresent();
            assertThat(result.get().isFraud()).isEqualTo(1);
            assertThat(result.get().isFlaggedFraud()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return empty for row with too few columns")
        void parseRow_shouldReturnEmpty_whenTooFewColumns() {
            String[] row = {"1", "TRANSFER", "50000.00"}; // only 3 columns

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when amount is not a number")
        void parseRow_shouldReturnEmpty_whenAmountNotNumeric() {
            String[] row = {"1", "TRANSFER", "not-a-number", "C100", "100000.00",
                    "50000.00", "C200", "0.00", "50000.00", "0", "0"};

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when step is not an integer")
        void parseRow_shouldReturnEmpty_whenStepNotInteger() {
            String[] row = {"abc", "TRANSFER", "50000.00", "C100", "100000.00",
                    "50000.00", "C200", "0.00", "50000.00", "0", "0"};

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should trim whitespace from CSV values")
        void parseRow_shouldTrimWhitespace() {
            String[] row = {" 1 ", " TRANSFER ", " 50000.00 ", " C100 ", "100000.00",
                    "50000.00", " C200 ", "0.00", "50000.00", " 0 ", " 0 "};

            Optional<PaySimRow> result = loader.parseRow(row, 1);

            assertThat(result).isPresent();
            assertThat(result.get().type()).isEqualTo("TRANSFER");
            assertThat(result.get().nameOrig()).isEqualTo("C100");
        }
    }

    // ==============================================================
    // validate() tests
    // ==============================================================

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("Should pass validation for a correct PaySim row")
        void validate_shouldReturnEmpty_whenRowIsValid() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", new BigDecimal("50000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isEmpty();
        }

        @Test
        @DisplayName("Should reject negative step value")
        void validate_shouldReject_whenStepIsNegative() {
            PaySimRow row = new PaySimRow(-1, "TRANSFER", new BigDecimal("1000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Invalid step");
        }

        @Test
        @DisplayName("Should reject step exceeding maximum (744)")
        void validate_shouldReject_whenStepExceedsMax() {
            PaySimRow row = new PaySimRow(745, "TRANSFER", new BigDecimal("1000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Invalid step");
        }

        @Test
        @DisplayName("Should accept step at maximum boundary (744)")
        void validate_shouldAccept_whenStepEqualsMax() {
            PaySimRow row = new PaySimRow(744, "TRANSFER", new BigDecimal("1000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isEmpty();
        }

        @Test
        @DisplayName("Should reject negative amount")
        void validate_shouldReject_whenAmountIsNegative() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", new BigDecimal("-500"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Negative amount");
        }

        @Test
        @DisplayName("Should accept zero amount")
        void validate_shouldAccept_whenAmountIsZero() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", BigDecimal.ZERO,
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isEmpty();
        }

        @Test
        @DisplayName("Should reject unknown transaction type")
        void validate_shouldReject_whenTypeIsUnknown() {
            PaySimRow row = new PaySimRow(1, "WIRE_TRANSFER", new BigDecimal("1000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Unknown transaction type");
        }

        @ParameterizedTest
        @ValueSource(strings = {"TRANSFER", "PAYMENT", "CASH_OUT", "CASH_IN", "DEBIT"})
        @DisplayName("Should accept all valid PaySim transaction types")
        void validate_shouldAccept_allValidTypes(String type) {
            PaySimRow row = new PaySimRow(1, type, new BigDecimal("100"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isEmpty();
        }

        @Test
        @DisplayName("Should reject empty sender account")
        void validate_shouldReject_whenSenderIsBlank() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", new BigDecimal("1000"),
                    "", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Empty sender or receiver");
        }

        @Test
        @DisplayName("Should reject empty receiver account")
        void validate_shouldReject_whenReceiverIsBlank() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", new BigDecimal("1000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "  ", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Optional<String> error = loader.validate(row, 1);

            assertThat(error).isPresent()
                    .get().asString().contains("Empty sender or receiver");
        }
    }

    // ==============================================================
    // toTransaction() tests
    // ==============================================================

    @Nested
    @DisplayName("toTransaction()")
    class ToTransactionTests {

        @Test
        @DisplayName("Should convert PaySimRow to Transaction with correct field mapping")
        void toTransaction_shouldMapAllFields() {
            PaySimRow row = new PaySimRow(10, "CASH_OUT", new BigDecimal("75000.00"),
                    "C100", new BigDecimal("100000.00"), new BigDecimal("25000.00"),
                    "C200", new BigDecimal("0.00"), new BigDecimal("75000.00"), 1, 0);

            Transaction tx = loader.toTransaction(row);

            assertThat(tx.getDatasetSource()).isEqualTo(DatasetSource.PAYSIM);
            assertThat(tx.getTransactionType()).isEqualTo(TransactionType.CASH_OUT);
            assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("75000.00"));
            assertThat(tx.getSenderAccount()).isEqualTo("C100");
            assertThat(tx.getReceiverAccount()).isEqualTo("C200");
            assertThat(tx.getSenderBalanceBefore()).isEqualByComparingTo(new BigDecimal("100000.00"));
            assertThat(tx.getSenderBalanceAfter()).isEqualByComparingTo(new BigDecimal("25000.00"));
            assertThat(tx.getReceiverBalanceBefore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(tx.getReceiverBalanceAfter()).isEqualByComparingTo(new BigDecimal("75000.00"));
            assertThat(tx.getIsFraud()).isTrue();
            assertThat(tx.getIsFlaggedFraud()).isFalse();
        }

        @Test
        @DisplayName("Should convert step to timestamp (1 step = 1 hour from base date)")
        void toTransaction_shouldConvertStepToTimestamp() {
            PaySimRow row = new PaySimRow(48, "TRANSFER", BigDecimal.TEN,
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Transaction tx = loader.toTransaction(row);

            // Base date is 2025-01-01T00:00, plus 48 hours = 2025-01-03T00:00
            assertThat(tx.getTimestamp()).isEqualTo(LocalDateTime.of(2025, 1, 3, 0, 0));
        }

        @Test
        @DisplayName("Should generate external ID containing PAYSIM prefix")
        void toTransaction_shouldGenerateExternalId() {
            PaySimRow row = new PaySimRow(1, "TRANSFER", new BigDecimal("50000"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Transaction tx = loader.toTransaction(row);

            assertThat(tx.getExternalId())
                    .startsWith("PAYSIM-")
                    .contains("C100")
                    .contains("C200")
                    .contains("50000");
        }

        @ParameterizedTest
        @CsvSource({
                "TRANSFER, TRANSFER",
                "PAYMENT, PAYMENT",
                "CASH_OUT, CASH_OUT",
                "CASH_IN, CASH_IN",
                "DEBIT, DEBIT"
        })
        @DisplayName("Should map all PaySim type strings to correct enum values")
        void toTransaction_shouldMapAllTypes(String input, String expected) {
            PaySimRow row = new PaySimRow(1, input, BigDecimal.TEN,
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO,
                    "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Transaction tx = loader.toTransaction(row);

            assertThat(tx.getTransactionType()).isEqualTo(TransactionType.valueOf(expected));
        }
    }
}

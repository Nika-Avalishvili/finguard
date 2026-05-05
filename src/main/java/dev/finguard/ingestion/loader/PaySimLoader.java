package dev.finguard.ingestion.loader;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.ingestion.dto.PaySimRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Maps a raw PaySim CSV row to a Transaction domain entity.
 *
 * <p>PaySim CSV columns (0-indexed):
 * step | type | amount | nameOrig | oldbalanceOrg | newbalanceOrig |
 * nameDest | oldbalanceDest | newbalanceDest | isFraud | isFlaggedFraud
 *
 * <p>"step" represents hours elapsed since the simulation start (1 step = 1 hour).
 * We convert steps to timestamps using a configurable base date.
 */
@Component
public class PaySimLoader {

    private static final Logger log = LoggerFactory.getLogger(PaySimLoader.class);

    /**
     * Base date for converting PaySim time steps to timestamps.
     * PaySim doesn't use real dates — steps are hour offsets from an arbitrary start.
     */
    private static final LocalDateTime BASE_DATE = LocalDateTime.of(2025, 1, 1, 0, 0);

    /**
     * Parse a raw CSV string array into a PaySimRow record.
     *
     * @param row     raw CSV columns
     * @param rowNum  row number in file (for error reporting)
     * @return parsed PaySimRow, or empty if the row is malformed
     */
    public Optional<PaySimRow> parseRow(String[] row, long rowNum) {
        try {
            if (row.length < 11) {
                log.warn("Row {}: insufficient columns ({}), expected 11", rowNum, row.length);
                return Optional.empty();
            }

            int step = Integer.parseInt(row[0].trim());
            String type = row[1].trim();
            BigDecimal amount = new BigDecimal(row[2].trim());
            String nameOrig = row[3].trim();
            BigDecimal oldBalanceOrig = new BigDecimal(row[4].trim());
            BigDecimal newBalanceOrig = new BigDecimal(row[5].trim());
            String nameDest = row[6].trim();
            BigDecimal oldBalanceDest = new BigDecimal(row[7].trim());
            BigDecimal newBalanceDest = new BigDecimal(row[8].trim());
            int isFraud = Integer.parseInt(row[9].trim());
            int isFlaggedFraud = Integer.parseInt(row[10].trim());

            return Optional.of(new PaySimRow(
                    step, type, amount, nameOrig, oldBalanceOrig, newBalanceOrig,
                    nameDest, oldBalanceDest, newBalanceDest, isFraud, isFlaggedFraud));

        } catch (NumberFormatException e) {
            log.warn("Row {}: number format error — {}", rowNum, e.getMessage());
            return Optional.empty();
        } catch (ArrayIndexOutOfBoundsException e) {
            log.warn("Row {}: array index error — {}", rowNum, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validate a parsed PaySimRow against business rules.
     *
     * @param row    parsed row
     * @param rowNum row number for error reporting
     * @return validation error message, or empty if valid
     */
    public Optional<String> validate(PaySimRow row, long rowNum) {
        if (row.step() < 0 || row.step() > PaySimRow.MAX_STEP) {
            return Optional.of("Invalid step value: " + row.step());
        }

        if (row.amount().compareTo(BigDecimal.ZERO) < 0) {
            return Optional.of("Negative amount: " + row.amount());
        }

        if (resolveTransactionType(row.type()) == null) {
            return Optional.of("Unknown transaction type: " + row.type());
        }

        if (row.nameOrig().isBlank() || row.nameDest().isBlank()) {
            return Optional.of("Empty sender or receiver account");
        }

        return Optional.empty();
    }

    /**
     * Convert a validated PaySimRow to a Transaction entity.
     *
     * @param row validated PaySimRow
     * @return Transaction entity ready for persistence
     */
    public Transaction toTransaction(PaySimRow row) {
        Transaction tx = new Transaction();

        // External ID: combination of step + sender + receiver + amount for uniqueness
        tx.setExternalId(String.format("PAYSIM-%d-%s-%s-%s",
                row.step(), row.nameOrig(), row.nameDest(), row.amount().toPlainString()));

        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setTimestamp(BASE_DATE.plusHours(row.step()));
        tx.setSenderAccount(row.nameOrig());
        tx.setReceiverAccount(row.nameDest());
        tx.setTransactionType(resolveTransactionType(row.type()));
        tx.setAmount(row.amount());
        tx.setSenderBalanceBefore(row.oldBalanceOrig());
        tx.setSenderBalanceAfter(row.newBalanceOrig());
        tx.setReceiverBalanceBefore(row.oldBalanceDest());
        tx.setReceiverBalanceAfter(row.newBalanceDest());
        tx.setIsFraud(row.isFraud() == 1);
        tx.setIsFlaggedFraud(row.isFlaggedFraud() == 1);

        return tx;
    }

    /**
     * Map PaySim type string to TransactionType enum.
     * PaySim uses uppercase names: TRANSFER, PAYMENT, CASH_OUT, CASH_IN, DEBIT.
     */
    private TransactionType resolveTransactionType(String type) {
        return switch (type.toUpperCase()) {
            case "TRANSFER" -> TransactionType.TRANSFER;
            case "PAYMENT" -> TransactionType.PAYMENT;
            case "CASH_OUT" -> TransactionType.CASH_OUT;
            case "CASH_IN" -> TransactionType.CASH_IN;
            case "DEBIT" -> TransactionType.DEBIT;
            default -> null;
        };
    }
}

/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.account.payment_method;

import bisq.account.protocol_type.TradeProtocolType;

import java.util.List;

public class CryptoPaymentUtil {
    public static CryptoPaymentMethod from(String paymentMethodName, String currencyCode) {
        try {
            return new CryptoPaymentMethod(CryptoPaymentRail.valueOf(paymentMethodName), currencyCode);
        } catch (Throwable ignore) {
            return new CryptoPaymentMethod(paymentMethodName, currencyCode);
        }
    }

    public static List<CryptoPaymentRail> getCryptoPaymentRails() {
        return List.of(CryptoPaymentRail.values());
    }

    public static List<CryptoPaymentRail> getCryptoPaymentRails(TradeProtocolType protocolType) {
        switch (protocolType) {
            case BISQ_EASY:
                throw new IllegalArgumentException("No support for CryptoPaymentMethods for BISQ_EASY");
            case BISQ_MULTISIG:
            case LIGHTNING_X:
                // BTC to alt-coin trade use case
                return getCryptoPaymentRails();
            case MONERO_SWAP:
            case LIQUID_SWAP:
            case BSQ_SWAP:
                return List.of(CryptoPaymentRail.NATIVE_CHAIN);
            default:
                throw new RuntimeException("Not handled case: protocolType=" + protocolType);
        }
    }
}
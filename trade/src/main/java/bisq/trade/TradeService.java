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

package bisq.trade;

import bisq.common.application.Service;
import bisq.contract.ContractService;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.offer.OfferService;
import bisq.persistence.PersistenceService;
import bisq.support.SupportService;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Getter
public class TradeService implements Service {
    private final BisqEasyTradeService bisqEasyTradeService;

    public TradeService(NetworkService networkService,
                        IdentityService identityService,
                        PersistenceService persistenceService,
                        OfferService offerService,
                        ContractService contractService,
                        SupportService supportService) {

        bisqEasyTradeService = new BisqEasyTradeService(networkService,
                identityService,
                persistenceService,
                offerService,
                contractService,
                supportService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////
    // Service
    ///////////////////////////////////////////////////////////////////////////////////////////////////

    public CompletableFuture<Boolean> initialize() {
        log.info("initialize");
        return bisqEasyTradeService.initialize();

    }

    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        return bisqEasyTradeService.shutdown();
    }
}
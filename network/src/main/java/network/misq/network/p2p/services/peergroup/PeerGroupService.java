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

package network.misq.network.p2p.services.peergroup;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import network.misq.common.timer.Scheduler;
import network.misq.common.util.CompletableFutureUtils;
import network.misq.network.p2p.node.Address;
import network.misq.network.p2p.node.CloseReason;
import network.misq.network.p2p.node.Connection;
import network.misq.network.p2p.node.Node;
import network.misq.network.p2p.services.peergroup.exchange.PeerExchangeService;
import network.misq.network.p2p.services.peergroup.exchange.PeerExchangeStrategy;
import network.misq.network.p2p.services.peergroup.keepalive.KeepAliveService;
import network.misq.network.p2p.services.peergroup.validateaddress.AddressValidationService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static network.misq.network.p2p.node.CloseReason.*;

@Slf4j
public class PeerGroupService {

    private final Node node;
    private final BannList bannList;
    private final Config config;
    @Getter
    private final PeerGroup peerGroup;
    private final PeerExchangeService peerExchangeService;
    private final KeepAliveService keepAliveService;
    private final AddressValidationService addressValidationService;
    private Optional<Scheduler> scheduler = Optional.empty();

    public static record Config(PeerGroup.Config peerGroupConfig,
                                PeerExchangeStrategy.Config peerExchangeConfig,
                                KeepAliveService.Config keepAliveServiceConfig,
                                long bootstrapTime,
                                long interval,
                                long timeout,
                                long maxAge,
                                int maxReported,
                                int maxPersisted,
                                int maxSeeds) {
    }

    public PeerGroupService(Node node, BannList bannList, Config config, List<Address> seedNodeAddresses) {
        this.node = node;
        this.bannList = bannList;
        this.config = config;
        peerGroup = new PeerGroup(node, config.peerGroupConfig, seedNodeAddresses, bannList);
        peerExchangeService = new PeerExchangeService(node, new PeerExchangeStrategy(peerGroup, config.peerExchangeConfig()));
        keepAliveService = new KeepAliveService(node, peerGroup, config.keepAliveServiceConfig());
        addressValidationService = new AddressValidationService(node, bannList);
    }

    public CompletableFuture<Boolean> initialize() {
        return peerExchangeService.doInitialPeerExchange()
                .thenCompose(__ -> {
                    scheduler = Optional.of(Scheduler.run(this::runBlockingTasks)
                            .periodically(config.interval())
                            .name("PeerGroupService.scheduler-" + node));
                    keepAliveService.initialize();
                    return CompletableFuture.completedFuture(true);
                });
    }

    private void runBlockingTasks() {
        closeBanned()
                .thenCompose(__ -> maybeVerifyInboundConnections())
                .thenComposeAsync(__ -> maybeCloseDuplicateConnections(), delayedExecutor(100, MILLISECONDS))
                .thenComposeAsync(__ -> maybeCloseConnectionsToSeeds(), delayedExecutor(100, MILLISECONDS))
                .thenComposeAsync(__ -> maybeCloseAgedConnections(), delayedExecutor(100, MILLISECONDS))
                .thenComposeAsync(__ -> maybeCloseExceedingInboundConnections(), delayedExecutor(100, MILLISECONDS))
                .thenComposeAsync(__ -> maybeCloseExceedingConnections(), delayedExecutor(100, MILLISECONDS))
                .thenComposeAsync(__ -> maybeCreateConnections(), delayedExecutor(100, MILLISECONDS))
                .thenRun(this::maybeRemoveReportedPeers)
                .thenRun(this::maybeRemovePersistedPeers);
    }

    public CompletableFuture<Void> shutdown() {
        peerExchangeService.shutdown();
        addressValidationService.shutdown();
        keepAliveService.shutdown();
        scheduler.ifPresent(Scheduler::stop);
        return CompletableFuture.completedFuture(null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////
    // Tasks
    ///////////////////////////////////////////////////////////////////////////////////////////////////

    private CompletableFuture<List<Connection>> closeBanned() {
        return CompletableFutureUtils.allOf(peerGroup.getAllConnections()
                        .filter(Connection::isRunning)
                        .filter(connection -> bannList.isBanned(connection.getPeerAddress()))
                        .peek(connection -> log.info("{} -> {}: CloseQuarantined triggered close connection", node, connection.getPeerAddress()))
                        .map(connection -> node.closeConnection(connection, CloseReason.BANNED)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }

    private CompletableFuture<List<Boolean>> maybeVerifyInboundConnections() {
        // We only do the verification in about 30% of calls to avoid getting too much churn
        if (new Random().nextInt(10) >= 3) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        Set<Address> outboundAddresses = peerGroup.getOutboundConnections()
                .filter(addressValidationService::isInProgress)
                .map(Connection::getPeerAddress)
                .collect(Collectors.toSet());
        return CompletableFutureUtils.allOf(peerGroup.getInboundConnections()
                        .filter(Connection::isRunning)
                        .filter(inbound -> !inbound.isPeerAddressVerified())
                        .filter(addressValidationService::isNotInProgress)
                        .filter(inbound -> !outboundAddresses.contains(inbound.getPeerAddress()))
                        .peek(inbound -> log.info("{} -> {}: Start addressValidationProtocol", node, inbound.getPeerAddress()))
                        .map(addressValidationService::startAddressValidationProtocol))
                .orTimeout(config.timeout(), MILLISECONDS);
    }

    /**
     * Remove duplicate connections (inbound connections which have an outbound connection with the same address)
     */
    private CompletableFuture<List<Connection>> maybeCloseDuplicateConnections() {
        Set<Address> outboundAddresses = peerGroup.getOutboundConnections()
                .filter(addressValidationService::isNotInProgress)
                .map(Connection::getPeerAddress)
                .collect(Collectors.toSet());
        return CompletableFutureUtils.allOf(peerGroup.getInboundConnections()
                        .filter(this::mayDisconnect)
                        .filter(inbound -> outboundAddresses.contains(inbound.getPeerAddress()))
                        .peek(inbound -> log.info("{} -> {}: Send CloseConnectionMessage as we have an " +
                                        "outbound connection with the same address.",
                                node, inbound.getPeerAddress()))
                        .map(inbound -> node.closeConnectionGracefully(inbound, DUPLICATE_CONNECTION)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }


    private CompletableFuture<List<Connection>> maybeCloseConnectionsToSeeds() {
        Comparator<Connection> comparator = peerGroup.getConnectionAgeComparator().reversed(); // reversed as we use skip
        return CompletableFutureUtils.allOf(peerGroup.getAllConnections()
                        .filter(this::mayDisconnect)
                        .filter(peerGroup::isASeed)
                        .sorted(comparator)
                        .skip(config.maxSeeds())
                        .peek(connection -> log.info("{} -> {}: Send CloseConnectionMessage as we have too " +
                                        "many connections to seeds.",
                                node, connection.getPeersCapability().address()))
                        .map(connection -> node.closeConnectionGracefully(connection, TOO_MANY_CONNECTIONS_TO_SEEDS)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }

    private CompletableFuture<List<Connection>> maybeCloseAgedConnections() {
        return CompletableFutureUtils.allOf(peerGroup.getAllConnections()
                        .filter(this::mayDisconnect)
                        .filter(connection -> connection.getMetrics().getAge() > config.maxAge())
                        .peek(connection -> log.info("{} -> {}: Send CloseConnectionMessage as the connection age " +
                                        "is too old.",
                                node, connection.getPeersCapability().address()))
                        .map(connection -> node.closeConnectionGracefully(connection, AGED_CONNECTION)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }

    private CompletableFuture<List<Connection>> maybeCloseExceedingInboundConnections() {
        Comparator<Connection> comparator = peerGroup.getConnectionAgeComparator().reversed();
        return CompletableFutureUtils.allOf(peerGroup.getInboundConnections()
                        .filter(this::mayDisconnect)
                        .sorted(comparator)
                        .skip(peerGroup.getMaxInboundConnections())
                        .peek(connection -> log.info("{} -> {}: Send CloseConnectionMessage as we have too many inbound connections.",
                                node, connection.getPeersCapability().address()))
                        .map(connection -> node.closeConnectionGracefully(connection, TOO_MANY_INBOUND_CONNECTIONS)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }


    private CompletableFuture<List<Connection>> maybeCloseExceedingConnections() {
        Comparator<Connection> comparator = peerGroup.getConnectionAgeComparator().reversed();
        return CompletableFutureUtils.allOf(peerGroup.getAllConnections()
                        .filter(this::mayDisconnect)
                        .sorted(comparator)
                        .skip(peerGroup.getMaxNumConnectedPeers())
                        .peek(connection -> log.info("{} -> {}: Send CloseConnectionMessage as we have too many connections.",
                                node, connection.getPeersCapability().address()))
                        .map(connection -> node.closeConnectionGracefully(connection, TOO_MANY_CONNECTIONS)))
                .orTimeout(config.timeout(), MILLISECONDS);
    }

    private CompletableFuture<Void> maybeCreateConnections() {
        int minNumConnectedPeers = peerGroup.getMinNumConnectedPeers();
        // We want to have at least 40% of our minNumConnectedPeers as outbound connections 
        if (getMissingOutboundConnections() <= 0) {
            // We have enough outbound connections, lets check if we have sufficient connections in total
            int numAllConnections = peerGroup.getNumConnections();
            int missing = minNumConnectedPeers - numAllConnections;
            if (missing <= 0) {
                return CompletableFuture.completedFuture(null);
            }
        }

        // We use the peer exchange protocol for establishing new connections.
        // The calculation how many connections we need is done inside PeerExchangeService/PeerExchangeStrategy

        int missingOutboundConnections = getMissingOutboundConnections();
        var a = peerGroup.getMinOutboundConnections();
        var b = peerGroup.getOutboundConnections().count();
        if (missingOutboundConnections <= 0) {
            // We have enough outbound connections, lets check if we have sufficient connections in total
            int numAllConnections = peerGroup.getNumConnections();
            int missing = minNumConnectedPeers - numAllConnections;
            if (missing <= 0) {
                return CompletableFuture.completedFuture(null);
            }
        }

        log.info("Node {} has not sufficient connections and calls peerExchangeService.doFurtherPeerExchange", node);
        return peerExchangeService.doFurtherPeerExchange()
                .orTimeout(config.timeout(), MILLISECONDS);
    }


    private void maybeRemoveReportedPeers() {
        List<Peer> reportedPeers = new ArrayList<>(peerGroup.getReportedPeers());
        int exceeding = reportedPeers.size() - config.maxReported();
        if (exceeding > 0) {
            reportedPeers.sort(Comparator.comparing(Peer::getDate));
            List<Peer> candidates = reportedPeers.subList(0, Math.min(exceeding, reportedPeers.size()));
            log.info("Remove {} reported peers: {}", candidates.size(), candidates);
            peerGroup.removeReportedPeers(candidates);
        }
    }

    private void maybeRemovePersistedPeers() {
        List<Peer> persistedPeers = new ArrayList<>(peerGroup.getPersistedPeers());
        int exceeding = persistedPeers.size() - config.maxPersisted();
        if (exceeding > 0) {
            persistedPeers.sort(Comparator.comparing(Peer::getDate));
            List<Peer> candidates = persistedPeers.subList(0, Math.min(exceeding, persistedPeers.size()));
            log.info("Remove {} persisted peers: {}", candidates.size(), candidates);
            peerGroup.removePersistedPeers(candidates);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////////////

    private boolean mayDisconnect(Connection connection) {
        return notBootstrapping(connection) &&
                addressValidationService.isNotInProgress(connection)
                && connection.isRunning();
    }

    private boolean notBootstrapping(Connection connection) {
        return connection.getMetrics().getAge() > config.bootstrapTime();
    }

    private int getMissingOutboundConnections() {
        return peerGroup.getMinOutboundConnections() - (int) peerGroup.getOutboundConnections().count();
    }
}
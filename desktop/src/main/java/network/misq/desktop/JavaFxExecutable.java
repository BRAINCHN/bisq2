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

package network.misq.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import network.misq.application.ApplicationOptions;
import network.misq.application.DefaultServiceProvider;
import network.misq.application.Executable;
import network.misq.common.annotations.LateInit;

import static java.util.Objects.requireNonNull;

@Slf4j
public class JavaFxExecutable extends Executable<DefaultServiceProvider> {
    @LateInit
    private StageController stageController;

    public JavaFxExecutable(String[] args) {
        super(args);
    }

    @Override
    protected DefaultServiceProvider createServiceProvider(ApplicationOptions applicationOptions, String[] args) {
        return new DefaultServiceProvider(applicationOptions, args);
    }

    @Override
    protected void launchApplication(String[] args) {
        new Thread(() -> {
            Thread.currentThread().setName("Java FX Application Launcher");
            Application.launch(JavaFXApplication.class, args); //blocks until app is closed
        }).start();

        JavaFXApplication.onApplicationLaunched
                .whenComplete((applicationData, throwable) -> {
                    if (throwable == null) {
                        stageController = new StageController(serviceProvider, applicationData);
                        log.info("Java FX Application launched");
                        onApplicationLaunched();
                    } else {
                        log.error("Could not launch JavaFX application.", throwable);
                        shutdown();
                    }
                });
    }

    @Override
    protected void onDomainInitialized() {
        Platform.runLater(() -> requireNonNull(stageController).onDomainInitialized());
    }

    @Override
    protected void onInitializeDomainFailed(Throwable throwable) {
        super.onInitializeDomainFailed(throwable);
        requireNonNull(stageController).onInitializeDomainFailed();
    }

    @Override
    public void shutdown() {
        if (stageController != null) {
            stageController.shutdown();
        } else {
            super.shutdown();
        }
    }
}
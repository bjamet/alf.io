/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;


import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PaymentInformation;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.Transaction;
import alfio.repository.TicketRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.util.Json;
import alfio.util.MonetaryUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.builder.api.DefaultApi20;
import com.github.scribejava.core.model.OAuthConfig;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.stripe.exception.*;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Fee;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static alfio.model.system.ConfigurationKeys.*;
import static org.apache.commons.lang3.StringUtils.*;

@Component
@Log4j2
public class StripeManager {

    public static final String STRIPE_UNEXPECTED = "error.STEP2_STRIPE_unexpected";
    public static final String CONNECT_REDIRECT_PATH = "/admin/configuration/payment/stripe/authorize";
    private final Map<Class<? extends StripeException>, StripeExceptionHandler> handlers;
    private final ConfigurationManager configurationManager;
    private final TicketRepository ticketRepository;
    private final ConfigurationRepository configurationRepository;

    public StripeManager(ConfigurationManager configurationManager,
                         TicketRepository ticketRepository,
                         ConfigurationRepository configurationRepository) {
        this.configurationManager = configurationManager;
        this.ticketRepository = ticketRepository;
        this.configurationRepository = configurationRepository;

        handlers = new HashMap<>();
        handlers.put(CardException.class, this::handleCardException);
        handlers.put(InvalidRequestException.class, this::handleInvalidRequestException);
        handlers.put(AuthenticationException.class, this::handleAuthenticationException);
        handlers.put(APIConnectionException.class, this::handleApiConnectionException);
        handlers.put(StripeException.class, this::handleGenericException);
    }

    private String getSecretKey(Event event) {
        return configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), STRIPE_SECRET_KEY));
    }

    private String getWebhookSignatureKey() {
        return configurationManager.getRequiredValue(Configuration.getSystemConfiguration(STRIPE_WEBHOOK_KEY));
    }

    String getPublicKey(Event event) {
        if(isConnectEnabled(event)) {
            return configurationManager.getRequiredValue(Configuration.getSystemConfiguration(STRIPE_PUBLIC_KEY));
        }
        return configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), STRIPE_PUBLIC_KEY));
    }

    public ConnectURL getConnectURL(Function<ConfigurationKeys, Configuration.ConfigurationPathKey> keyResolver) {
        String secret = configurationManager.getRequiredValue(keyResolver.apply(STRIPE_SECRET_KEY));
        String clientId = configurationManager.getRequiredValue(keyResolver.apply(STRIPE_CONNECT_CLIENT_ID));
        String callbackURL = configurationManager.getStringConfigValue(keyResolver.apply(STRIPE_CONNECT_CALLBACK), configurationManager.getRequiredValue(keyResolver.apply(BASE_URL)) + CONNECT_REDIRECT_PATH);
        String state = UUID.randomUUID().toString();
        String code = UUID.randomUUID().toString();
        OAuthConfig config = new OAuthConfig(clientId, secret, callbackURL, "read_write", null, state, "code", null, null, null);
        return new ConnectURL(new StripeConnectApi().getAuthorizationUrl(config, Collections.emptyMap()), state, code);
    }

    private boolean isConnectEnabled(Event event) {
        return configurationManager.getBooleanConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), PLATFORM_MODE_ENABLED), false);
    }

    public ConnectResult storeConnectedAccountId(String code, Function<ConfigurationKeys, Configuration.ConfigurationPathKey> keyResolver) {
        try {
            String connectClientID = configurationManager.getRequiredValue(keyResolver.apply(STRIPE_CONNECT_CLIENT_ID));
            String clientSecret = getSystemApiKey();
            OAuth20Service service = new ServiceBuilder(connectClientID).apiSecret(clientSecret).build(new StripeConnectApi());
            Map<String, String> token = Json.fromJson(service.getAccessToken(code).getRawResponse(), new TypeReference<Map<String, String>>() {});
            String accountId = token.get("stripe_user_id");
            if(accountId != null) {
                configurationManager.saveConfig(keyResolver.apply(ConfigurationKeys.STRIPE_CONNECTED_ID), accountId);
            }
            return new ConnectResult(accountId, accountId != null, token.get("error_message"));
        } catch (Exception e) {
            log.error("cannot retrieve account ID", e);
            return new ConnectResult(null, false, e.getMessage());
        }
    }

    private String getSystemApiKey() {
        return configurationManager.getRequiredValue(Configuration.getSystemConfiguration(STRIPE_SECRET_KEY));
    }

    public Optional<Boolean> processWebhookEvent(String body, String signature) {
        try {
            com.stripe.model.Event event = Webhook.constructEvent(body, signature, getWebhookSignatureKey());
            if(event.getType().equals("account.application.deauthorized")) {
                return Optional.of(revokeToken(event.getAccount()));
            }
            return Optional.of(true);
        } catch (Exception e) {
            log.error("got exception while handling stripe webhook", e);
            return Optional.empty();
        }
    }

    private boolean revokeToken(String accountId) {
        String key = ConfigurationKeys.STRIPE_CONNECTED_ID.getValue();
        Optional<Integer> optional = configurationRepository.findOrganizationIdByKeyAndValue(key, accountId);
        if(optional.isPresent()) {
            Integer organizationId = optional.get();
            log.warn("revoking access token {} for organization {}", accountId, organizationId);
            configurationManager.deleteOrganizationLevelByKey(key, organizationId, UserManager.ADMIN_USERNAME);
            return true;
        }
        return false;
    }

    /**
     * After client side integration with stripe widget, our server receives the stripeToken
     * StripeToken is a single-use token that allows our server to actually charge the credit card and
     * get money on our account.
     * <p>
     * as documented in https://stripe.com/docs/tutorials/charges
     *
     * @return
     * @throws StripeException
     */
    Charge chargeCreditCard(String stripeToken, long amountInCent, Event event,
                            String reservationId, String email, String fullName, String billingAddress) throws StripeException {

        int tickets = ticketRepository.countTicketsInReservation(reservationId);
        Map<String, Object> chargeParams = new HashMap<>();
        chargeParams.put("amount", amountInCent);
        Optional.ofNullable(calculateFee(event, tickets, amountInCent)).ifPresent(fee -> chargeParams.put("application_fee", fee));
        chargeParams.put("currency", event.getCurrency());
        chargeParams.put("card", stripeToken);

        chargeParams.put("description", String.format("%d ticket(s) for event %s", tickets, event.getDisplayName()));

        Map<String, String> initialMetadata = new HashMap<>();
        initialMetadata.put("reservationId", reservationId);
        initialMetadata.put("email", email);
        initialMetadata.put("fullName", fullName);
        if (StringUtils.isNotBlank(billingAddress)) {
            initialMetadata.put("billingAddress", billingAddress);
        }
        chargeParams.put("metadata", initialMetadata);
        return Charge.create(chargeParams, options(event));
    }

    private Long calculateFee(Event event, int numTickets, long amountInCent) {
        if(isConnectEnabled(event)) {
            String feeAsString = configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), PLATFORM_FEE), "0");
            String minimumFee = configurationManager.getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), PLATFORM_MINIMUM_FEE), "0");
            return new FeeCalculator(feeAsString, minimumFee, numTickets).calculate(amountInCent);
        }
        return null;
    }


    private RequestOptions options(Event event) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder();
        if(isConnectEnabled(event)) {
            //connected stripe account
            builder.setStripeAccount(configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), STRIPE_CONNECTED_ID)));
            return builder.setApiKey(getSystemApiKey()).build();
        }
        return builder.setApiKey(getSecretKey(event)).build();
    }

    Optional<PaymentInformation> getInfo(Transaction transaction, Event event) {
        try {
            RequestOptions options = options(event);
            Charge charge = Charge.retrieve(transaction.getTransactionId(), options);
            String paidAmount = MonetaryUtil.formatCents(charge.getAmount());
            String refundedAmount = MonetaryUtil.formatCents(charge.getAmountRefunded());
            List<Fee> fees = BalanceTransaction.retrieve(charge.getBalanceTransaction(), options).getFeeDetails();
            return Optional.of(new PaymentInformation(paidAmount, refundedAmount, getFeeAmount(fees, "stripe_fee"), getFeeAmount(fees, "application_fee")));
        } catch (StripeException e) {
            return Optional.empty();
        }
    }

    private String getFeeAmount(List<Fee> fees, String feeType) {
        return fees.stream()
            .filter(f -> f.getType().equals(feeType))
            .findFirst()
            .map(Fee::getAmount)
            .map(String::valueOf)
            .orElse(null);
    }

    // https://stripe.com/docs/api#create_refund
    public boolean refund(Transaction transaction, Event event, Optional<Integer> amount) {
        String chargeId = transaction.getTransactionId();
        try {
            String amountOrFull = amount.map(MonetaryUtil::formatCents).orElse("full");
            log.info("Stripe: trying to do a refund for payment {} with amount: {}", chargeId, amountOrFull);
            Map<String, Object> params = new HashMap<>();
            params.put("charge", chargeId);
            amount.ifPresent(a -> params.put("amount", a));
            if(isConnectEnabled(event)) {
                params.put("refund_application_fee", true);
            }
            Refund r = Refund.create(params, options(event));
            if("succeeded".equals(r.getStatus())) {
                log.info("Stripe: refund for payment {} executed with success for amount: {}", chargeId, amountOrFull);
                return true;
            } else {
                log.warn("Stripe: was not able to refund payment with id {}, returned status is not 'succeded' but {}", chargeId, r.getStatus());
                return false;
            }
        } catch (StripeException e) {
            log.warn("Stripe: was not able to refund payment with id " + chargeId, e);
            return false;
        }
    }

    String handleException(StripeException exc) {
        return findExceptionHandler(exc).handle(exc);
    }

    private StripeExceptionHandler findExceptionHandler(StripeException exc) {
        final Optional<StripeExceptionHandler> eh = Optional.ofNullable(handlers.get(exc.getClass()));
        if(!eh.isPresent()) {
            log.warn("cannot find an ExceptionHandler for {}. Falling back to the default one.", exc.getClass());
        }
        return eh.orElseGet(() -> handlers.get(StripeException.class));
    }

    /* exception handlers... */

    /**
     * This handler simply returns the message code from stripe.
     * There is no need in writing something in the log.
     * @param e the exception
     * @return the code
     */
    private String handleCardException(StripeException e) {
        CardException ce = (CardException)e;
        return "error.STEP2_STRIPE_" + ce.getCode();
    }

    /**
     * handles invalid request exception using the error.STEP2_STRIPE_invalid_ prefix for the message.
     * @param e the exception
     * @return message code
     */
    private String handleInvalidRequestException(StripeException e) {
        InvalidRequestException ire = (InvalidRequestException)e;
        return "error.STEP2_STRIPE_invalid_" + ire.getParam();
    }

    /**
     * Logs the failure and report the failure to the admin (to be done)
     * @param e the exception
     * @return error.STEP2_STRIPE_abort
     */
    private String handleAuthenticationException(StripeException e) {
        log.error("an AuthenticationException has occurred. Please fix configuration!!", e);
        return "error.STEP2_STRIPE_abort";
    }

    /**
     * Logs the failure and report the failure to the admin (to be done)
     * @param e
     * @return
     */
    private String handleApiConnectionException(StripeException e) {
        log.error("unable to connect to the Stripe API", e);
        return "error.STEP2_STRIPE_abort";
    }

    /**
     * Logs the failure and report the failure to the admin (to be done)
     * @param e
     * @return
     */
    private String handleGenericException(StripeException e) {
        log.error("unexpected error during transaction", e);
        return STRIPE_UNEXPECTED;
    }


    @FunctionalInterface
    private interface StripeExceptionHandler {
        String handle(StripeException exc);
    }

    private static class StripeConnectApi extends DefaultApi20 {

        @Override
        public String getAccessTokenEndpoint() {
            return "https://connect.stripe.com/oauth/token";
        }

        @Override
        protected String getAuthorizationBaseUrl() {
            return "https://connect.stripe.com/oauth/authorize";
        }
    }

    @Data
    public static class ConnectResult {
        private final String accountId;
        private final boolean success;
        private final String errorMessage;
    }

    @Data
    public static class ConnectURL {
        private final String authorizationURL;
        private final String state;
        private final String code;
    }

    private static class FeeCalculator {
        private final BigDecimal fee;
        private final BigDecimal minimumFee;
        private final boolean percentage;
        private final int numTickets;

        private FeeCalculator(String feeAsString, String minimumFeeAsString, int numTickets) {
            this.percentage = feeAsString.endsWith("%");
            this.fee = new BigDecimal(defaultIfEmpty(substringBefore(feeAsString, "%"), "0"));
            this.minimumFee = new BigDecimal(defaultIfEmpty(trimToNull(minimumFeeAsString), "0"));
            this.numTickets = numTickets;
        }

        private long calculate(long price) {
            long result = percentage ? MonetaryUtil.calcPercentage(price, fee, BigDecimal::longValueExact) : MonetaryUtil.unitToCents(fee);
            long minFee = MonetaryUtil.unitToCents(minimumFee, BigDecimal::longValueExact) * numTickets;
            return Math.max(result, minFee);
        }
    }

}

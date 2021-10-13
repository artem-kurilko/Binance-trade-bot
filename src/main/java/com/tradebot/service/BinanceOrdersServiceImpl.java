package com.tradebot.service;

import com.ibm.icu.text.CharsetDetector;
import com.tradebot.security.HmacSHA256Signer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.apache.commons.math3.util.Precision;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import static java.lang.Float.parseFloat;
import static java.lang.String.valueOf;

@Slf4j
public final class BinanceOrdersServiceImpl implements OrdersService {
    private static final String API = "3lhadKcEVRIEjKz8aaqPvNlacGDM3K03rWDRpWKH6BHJc6PEYh6sWvRJfvY3kUWM";
    private static final String SECRET_KEY = "uxANOJ8rPerXwEQwHYZb2LQ8S4FSmxwP7ayY59Og6OCi9UqW25FBSQhVykYhF54S";
    private static final String CURRENCY_PAIR = "BTCUSDT";
    private static final float BUY_PRICE_COEFFICIENT = (float) 0.998;
    private static final float SELL_PRICE_COEFFICIENT = (float) 1.004;

    // Exchange API endpoints
    private static final String BINANCE_BASE_URL = "https://api.binance.com";
    private static final String BINANCE_ORDER_URL = BINANCE_BASE_URL + "/api/v3/order";
    private static final String BINANCE_ACTIVE_ORDERS_URL = BINANCE_BASE_URL + "/api/v3/openOrders";
    private static final String BINANCE_TRADE_HISTORY_URL = BINANCE_BASE_URL + "/api/v3/myTrades";
    private static final String BINANCE_BALANCE_URL = BINANCE_BASE_URL + "/sapi/v1/capital/config/getall";
    private static final String BINANCE_AVERAGE_PRICE_URL = BINANCE_BASE_URL + "/api/v3/avgPrice";

    private final OkHttpClient client = new OkHttpClient.Builder().build();

    private static final long timestamp = System.currentTimeMillis();
    private static final long recvWindow = 60_000L;
    private static final int accuracy = 5;

    @Override
    //FIXME: check logic
    public void placeOrder(boolean isBuy) throws Exception {
        float btcBalance = getTotalBalance("BTC");
        float usdtBalance = getTotalBalance("USDT");
        String price;
        String quantity;
        String side;

        if (isBuy) {
            if (usdtBalance <= 1){
                log.info("Not enough USD for placing buy order");
                return;
            }

            price = valueOf(Precision.round(getAveragePrice() * BUY_PRICE_COEFFICIENT, accuracy));

            if (usdtBalance*0.25 >= usdtBalance)
                quantity = valueOf(Precision.round(usdtBalance / getAveragePrice(), accuracy, BigDecimal.ROUND_DOWN));
            else
                quantity = valueOf(Precision.round(usdtBalance * 0.25 / getAveragePrice(), accuracy, BigDecimal.ROUND_DOWN));
            side = "buy";
        }
        else {
            if(btcBalance * getAveragePrice() <= 1){
                log.info("Not enough BTC for placing sell order");
                return;
            }
            price = "0.0";
            quantity = "0.0";

            // если последний выполненный ордер был на покупку, взять его кол-во
            JSONObject lastExecutedOrder = getLastOrdersHistory();

            if (lastExecutedOrder.has("side") && lastExecutedOrder.getString("side").equals("buy")){
                quantity = lastExecutedOrder.getString("quantity");
                if (getActiveOrders().length() == 0)
                    price = valueOf(Precision.round(parseFloat(lastExecutedOrder.getString("price")) * SELL_PRICE_COEFFICIENT, accuracy));
                else
                    price = valueOf(Precision.round(parseFloat(lastExecutedOrder.getString("price")) * 1.05, accuracy));
            }

            // если последний выполненный ордер был на продажу, взять четверть баланса
            else{
                if (getActiveOrders().length() == 0) {
                    if (btcBalance * 0.25 >= btcBalance)
                        quantity = valueOf(Precision.round(btcBalance, accuracy, BigDecimal.ROUND_DOWN));
                    else
                        quantity = valueOf(Precision.round(btcBalance * 0.25, accuracy, BigDecimal.ROUND_DOWN));

                    price = valueOf(Precision.round(getAveragePrice() * SELL_PRICE_COEFFICIENT, accuracy));
                }
            } side="sell";
        }

        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_ORDER_URL)).newBuilder();
        urlBuilder.addQueryParameter("symbol", CURRENCY_PAIR);
        urlBuilder.addQueryParameter("side", side);
        urlBuilder.addQueryParameter("price", price);
        urlBuilder.addQueryParameter("quantity", quantity);
        urlBuilder.addQueryParameter("type", "LIMIT");
        urlBuilder.addQueryParameter("timeInForce", "GTC");
        addTimestampAndRecvWindow(urlBuilder);
        signRequest(urlBuilder);

        Response response = getResponse(urlBuilder,"POST",true);
        checkResponseStatus(response,"Exception while placing binance " + side + " order.");
        log.info("place " + side + " order.");
        assert response.body() != null : "Response body is null";
        response.body().close();
    }

    @Override
    public float getTotalBalance(String symbol) throws Exception {
        float totalBalance;

        if (symbol.equals("BTC"))
            totalBalance = (float) (parseFloat(getBTCBalance()) + (parseFloat(getUSDTBalance()) / getAveragePrice()));
        else
            totalBalance = (float) ((parseFloat(getBTCBalance()) * getAveragePrice()) + parseFloat(getUSDTBalance()));
        return totalBalance;
    }

    @Override
    public JSONArray getActiveOrders() throws IOException {
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_ACTIVE_ORDERS_URL)).newBuilder();
        addTimestampAndRecvWindow(urlBuilder);
        signRequest(urlBuilder);
        Response response = getResponse(urlBuilder,"GET",true);
        checkResponseStatus(response,"Error while getting active orders.");
        assert response.body() != null : "Response body is null";
        JSONArray activeOrders = new JSONArray(response.body().string());
        response.body().close();
        return activeOrders;
    }

    @Override
    public JSONObject getLastActiveOrder() throws Exception{
        if (getActiveOrders().length() >= 1)
            return getActiveOrders().getJSONObject(0);
        else return new JSONObject();
    }

    @Override
    public JSONArray getOrdersHistory() throws Exception {
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_TRADE_HISTORY_URL)).newBuilder();
        addTimestampAndRecvWindow(urlBuilder);
        urlBuilder.addQueryParameter("symbol", CURRENCY_PAIR);
        signRequest(urlBuilder);
        Response response = getResponse(urlBuilder,"GET",true);
        checkResponseStatus(response, "Error while getting orders history.");
        try {
            assert response.body() != null : "Response body is null";
            JSONArray orderHistory = new JSONArray(response.body().string());
            response.body().close();
            return orderHistory;
        } catch (NullPointerException e){
            log.info("Error while getting orders history. " + e.toString());
        }
        return new JSONArray();
    }

    @Override
    public JSONObject getLastOrdersHistory(){
        try {
            return getOrdersHistory().getJSONObject(0);
        } catch (Exception e){
            log.info("Error while getting last order history");
        }
        return new JSONObject();
    }

    @Override
    public void cancelOrder(String clientOrderId) throws Exception {
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_ORDER_URL)).newBuilder();
        addTimestampAndRecvWindow(urlBuilder);
        urlBuilder.addQueryParameter("origClientOrderId", clientOrderId);
        urlBuilder.addQueryParameter("symbol", CURRENCY_PAIR);
        signRequest(urlBuilder);
        Response response = getResponse(urlBuilder,"DELETE",true);
        checkResponseStatus(response,"Error while canceling order: " + clientOrderId);
        log.info("order has been canceled, clientOrderId: " + clientOrderId);
        assert response.body() != null : "Response body is null";
        response.body().close();
    }

    @Override
    public String getNFTBoxes() throws Exception {
        String url = "https://binance.com/bapi/accounts/v1/private/account/user/base-detail";
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
        Response response = csrfAuth(urlBuilder);
        checkResponseStatus(response, "Error while getting nft boxes");
        if (response.code() != 200)
            throw new RuntimeException("Exception while getting nft boxes. Status: " + response.code());

        assert(response.body() != null);
        return response.body().string();
    }

    private Response csrfAuth(HttpUrl.Builder urlBuilder) throws IOException {
        String url = urlBuilder.build().toString();
        String csrfToken = "e3eccabe24fbcb23ad78bc01dd037ba0";
        String cookie = "cid=ogZdxAVx; _ga=GA1.2.149864682.1631688019; bnc-uuid=e5d3d009-568a-4e74-a047-15f89e0d0709; campaign=www.google.com; source=organic; nft-init-compliance=true; home-ui-ab=A; fiat-prefer-currency=UAH; _gid=GA1.2.1428091926.1632689441; _h_desk_key=1d8e49ca86c7439ea86c6b4a9cecf6a8; userPreferredCurrency=RUB_USD; sensorsdata2015jssdkcross={\\\"distinct_id\\\":\\\"39079923\\\",\\\"first_id\\\":\\\"17be82f57323bb-03a91a02147404-581e311d-1440000-17be82f57337cd\\\",\\\"props\\\":{\\\"$latest_traffic_source_type\\\":\\\"ç\u009B´æ\u008E¥æµ\u0081é\u0087\u008F\\\",\\\"$latest_search_keyword\\\":\\\"æ\u009Cªå\u008F\u0096å\u0088°å\u0080¼_ç\u009B´æ\u008E¥æ\u0089\u0093å¼\u0080\\\",\\\"$latest_referrer\\\":\\\"\\\"},\\\"$device_id\\\":\\\"17be82f57323bb-03a91a02147404-581e311d-1440000-17be82f57337cd\\\"}; BNC_FV_KEY=3104c3b4e480a58c9885edff3e64f836f305029a; BNC_FV_KEY_EXPIRE=1633022402251; gtId=f8871226-5f09-4e93-8018-9a68ba22f813; s9r1=12364687DA8E315D2E13411D88FB3371; lang=ru; cr00=37398778C80D63F7D8246503BF32ED17; d1og=web.39079923.400278C4FD4F4F2BD9430281014E21A5; r2o1=web.39079923.951D1F867B148CD11C66C24C1DB0B477; f30l=web.39079923.D7404AF7365AB57D4E7ED99DE31228D9; logined=y; isAccountsLoggedIn=y; __BINANCE_USER_DEVICE_ID__={\\\"c3508c5ce64b615279eb64b805ec2d23\\\":{\\\"date\\\":1632936050788,\\\"value\\\":\\\"1632936054158kCFG3oeZQerGNujD552\\\"}}; p20t=web.39079923.5CF84DF4A88483130D9152F59D59B731; _gat=1";

        final RequestBody formBody = new FormBody.Builder().build();
        Request request = new Request.Builder().url(url)
                .headers(Headers.of(
                        "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                        "clienttype", "web",
                        "cookie", cookie,
                        "csrftoken", csrfToken))
                .post(formBody).build();
        Call call = client.newCall(request);
        return call.execute();
    }

    @Override
    public double getAveragePrice() throws Exception {
        String currencyPair = "BTC" + "USDT";
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_AVERAGE_PRICE_URL)).newBuilder();
        urlBuilder.addQueryParameter("symbol", currencyPair);
        Response response = getResponse(urlBuilder,"GET",false);
        checkResponseStatus(response,"Error while getting average price.");
        assert response.body() != null : "Response body is null";
        JSONObject avgPrice = new JSONObject(response.body().string());
        response.body().close();
        return Precision.round(Double.parseDouble(avgPrice.getString("price")), accuracy);
    }

    @Override
    public String getCurrencyBalance(String symbol) throws Exception {
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(BINANCE_BALANCE_URL)).newBuilder();
        addTimestampAndRecvWindow(urlBuilder);
        signRequest(urlBuilder);
        Response response = getResponse(urlBuilder, "GET", true);
        checkResponseStatus(response,"Error while getting currency balance.");
        assert response.body() != null : "Response body is null";
        JSONArray accountBalance = new JSONArray(response.body().string());
        response.body().close();
        for (int i = 0; i < accountBalance.length(); i++){
            if (accountBalance.getJSONObject(i).getString("coin").equals(symbol))
                return accountBalance.getJSONObject(i).getString("free");
        }
        return "0.0";
    }

    @Override
    public String getBTCBalance() throws Exception {
        return getCurrencyBalance("BTC");
    }

    @Override
    public String getUSDTBalance() throws Exception {
        return getCurrencyBalance("USDT");
    }

    /**
     * Adds timestamp and recvWindow parameters to request
     * @param urlBuilder HttpUrl.Builder instance
     */
    private void addTimestampAndRecvWindow(HttpUrl.Builder urlBuilder){
        urlBuilder.addQueryParameter("recvWindow", String.valueOf(recvWindow));
        urlBuilder.addQueryParameter("timestamp", String.valueOf(timestamp));
    }

    /**
     * Adds signature paramter to request
     * @param urlBuilder HttpUrl.Builder instance
     */
    private void signRequest(HttpUrl.Builder urlBuilder){
        String url = urlBuilder.build().toString();
        urlBuilder.addQueryParameter("signature", HmacSHA256Signer.sign(getQueryParameters(url), SECRET_KEY));
    }

    /**
     * Returns request's parameters
     * @param url request link
     * @return string value
     */
    private String getQueryParameters(String url){
        String[] queryParts = url.split("\\?");
        return queryParts[1];
    }

    /**
     * Creates request from urlBuilder and receive response.
     * @param urlBuilder request address
     * @param requiredAuthorization boolean value if api key header is needed
     * @return Response instance
     */
    private Response getResponse(HttpUrl.Builder urlBuilder, String method, boolean requiredAuthorization) throws IOException {
        String url = urlBuilder.build().toString();
        final RequestBody formBody = new FormBody.Builder().build();

        Request request;
        if (!requiredAuthorization)
            request = new Request.Builder().url(url).build();
        else {
            switch (method){
                case "GET":
                    request = new Request.Builder().url(url).addHeader("X-MBX-APIKEY", API).build();
                    break;

                case "POST":
                    request = new Request.Builder().url(url).addHeader("X-MBX-APIKEY", API).post(formBody).build();
                    break;

                case "DELETE":
                    request = new Request.Builder().url(url).addHeader("X-MBX-APIKEY", API).delete().build();
                    break;

                default:
                    throw new IOException("Error while getting response. Http method " + method + " not found.");
            }
        }
        Call call = client.newCall(request);
        return call.execute();
    }

    /**
     * Checks response status and log result.
     * @param response request response
     * @param errorMessage string message
     */
    private void checkResponseStatus(Response response, String errorMessage){
        if (!response.isSuccessful())
            log.info(errorMessage + " Error code: " + response.code() + " Error messaage: " + response.message());
    }

}

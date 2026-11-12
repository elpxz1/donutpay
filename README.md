# DonutPay (Fabric 1.21.11, client-side)

When someone pays you, an orange row shows in chat:

    » [2x] [Bal] [Payback]  Steve paid $200K

- **[2x]**: pays them back double (`/pay Steve 400000`)
- **[Bal]**: runs `/bal Steve`
- **[Payback]**: pays back exactly what they sent
- Every payment can only be paid back once, so double clicks can't send twice.
- Nothing is sent unless you click.

## Build the jar

Needs JDK 21. In this folder run:

    gradle build        (or ./gradlew build if you have the wrapper)

Jar ends up in `build/libs/donutpay-1.0.0.jar`. Drop it in `.minecraft/mods` with Fabric Loader + Fabric API.

## Testing

`/dpay test Steve 200000` fakes a payment so you can see the buttons without anyone paying you.

## If buttons don't show for real payments

The mod matches lines like `Steve paid you $200K`. If DonutSMP words it differently,
edit the `PAID` regex at the top of `DonutPayClient.java`.

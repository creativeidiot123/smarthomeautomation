## App content and review declarations

Keep declarations versioned as release evidence even when they are entered manually in Play Console. The declaration must match the behavior of every currently distributed artifact and SDK, not only the newest source branch.

### Privacy policy

- Provide an active, publicly accessible, non-geofenced, non-editable web URL in the required Play Console field; do not use a PDF where current policy prohibits it.
- Name the app or publishing entity consistently and disclose access, collection, use, sharing, protection, retention, and deletion of personal and sensitive data.
- Include an in-app privacy-policy link or text where policy requires it and make it readily discoverable.
- Keep the policy accurate for all SDKs, backend services, regions, account types, and feature flags currently distributed.
- A generic template, inaccessible page, login wall, editable document, placeholder, or policy belonging to a different entity/app is not acceptable evidence.

### Data safety

- Build the form from an actual data inventory covering app code, backend, WebView, SDKs, native code, analytics, ads, diagnostics, and conditional features.
- Declare collection, sharing, purpose, optional/required status, encryption in transit, deletion, and other requested properties according to current definitions.
- Treat the declaration as the global union of practices across distributed versions, regions, and user groups when current Play guidance requires that representation.
- A permission alone does not necessarily mean collection, but actual SDK/API behavior does. Inspect runtime and provider behavior rather than guessing from manifest names.
- Update the form whenever a release, SDK, backend, region, feature flag, or retention policy changes relevant data practice.
- Reconcile four surfaces before release: implementation, privacy policy, in-app disclosure/consent, and Data safety form.
- Do not assume an SDK vendor's template is complete for the app's configuration. The publisher remains responsible.

### Ads declaration

- Declare ads accurately, including third-party ads, native ads, banners/interstitials, and relevant house ads under current guidance.
- Keep the store label, target audience, ad SDKs, consent behavior, content rating, and ad content controls consistent.
- Paid product placement, cross-promotion, and offers may have separate legal/policy implications even when not classified as the Play “Contains ads” label.

### App access and review credentials

- Give reviewers stable access to every restricted feature, paywall, role, membership, region, and workflow needed for review.
- Provide active demo credentials and exact steps. Avoid expiring passwords, OTP-only flows, device-bound enrollment, inaccessible enterprise identity, location gates, or MFA without a reliable review path.
- Explain nonstandard fields, QR codes, role switching, hardware prerequisites, and test data clearly.
- Keep reviewer accounts funded/configured enough to exercise flows without exposing real user data or production authority.
- Test the instructions from a clean Play-installed build and an unrelated account/device before submission.
- Update access instructions whenever authentication, roles, paywalls, navigation, or test infrastructure changes.

### Target audience, Families, and age restrictions

- Select only age groups the app was deliberately designed for and is appropriate for. Store imagery, language, characters, onboarding, ads, content, and actual users must support the declaration.
- If any selected audience includes children, apply the current Families requirements across content, ads, SDKs, data use, identifiers, social features, purchases, and disclosures.
- Use only eligible/certified ad SDKs where required. Configure SDKs for child-directed and unknown-age users according to current rules.
- Use a neutral age screen only when it is genuinely neutral and policy-compatible; do not encourage users to misstate age.
- Age gates are risk controls, not substitutes for child-safety design, moderation, privacy, or legal obligations.
- Revisit target audience when the product, creative assets, social features, or monetization changes.

### Content rating

- Complete the questionnaire accurately for app content, interactive elements, UGC, purchases, ads, violence, language, sexuality, gambling, and other rated material.
- Re-submit when content or features materially change.
- Ensure ads and offers are appropriate for the app's content rating.
- Do not describe or screenshot a milder app than the binary actually delivers.

### Account deletion

- When the app offers account creation under current policy definitions, provide a readily discoverable in-app deletion initiation and an external web resource as required.
- Delete the account and associated user data subject only to clearly disclosed legitimate retention obligations; distinguish account disablement from deletion.
- Cover linked identities, subscriptions, server data, uploaded content, backups, tokens, devices, and processors.
- Make authentication and abuse safeguards proportional; do not create an obstructive dark pattern.
- Reflect deletion behavior in the privacy policy and Data safety declaration and test end to end.

### Sensitive permissions and APIs

- Request only permissions/APIs necessary for current core functionality promoted in the store listing.
- Request incrementally and in context; respect denial and provide reasonable alternatives.
- Complete current declaration/approval workflows for restricted permissions and special APIs before release.
- Audit release manifests for transitive SDK permissions. Remove unused permission declarations rather than relying on not calling the API.
- Re-verify current policy for SMS/Call Log, broad photo/video access, all-files access, accessibility, VPN, package installation, exact alarms, full-screen intent, foreground-service types, health data, background location, advertising ID, and any other high-risk capability.
- A technically granted Android permission does not imply Play policy eligibility.

### Feature-specific declarations

Determine whether the current App content page or policy requires declarations or evidence for:

- financial features and required regional licenses/disclosures;
- health/medical features, health-data access, regulatory status, hardware dependencies, and disclaimers;
- government affiliation or communication of government information;
- news and magazine functionality;
- social, dating, or child-safety standards;
- ads and advertising ID;
- target audience and Families;
- AI-generated content;
- COVID/health classifications if still surfaced by current policy;
- other new declarations shown in Play Console or policy announcements.

Complete “none” declarations when required; absence of the feature may still require certification.

## Policy classification and implementation

Before submission, inspect the current full policy under every applicable category. At minimum classify the app against:

### Restricted content

- child endangerment and child safety;
- inappropriate content, hate, violence, sexual content, bullying, dangerous products, sensitive events, tobacco/alcohol, and other restricted content;
- financial services and loans;
- real-money gambling, games, contests, and regional eligibility;
- illegal activities;
- user-generated content;
- health content and services;
- blockchain-based content;
- AI-generated content;
- age-restricted content and functionality.

### Privacy, deception, and device abuse

- user data, privacy policy, prominent disclosure, consent, security, retention, and account deletion;
- permissions and APIs that access sensitive information;
- device and network abuse, dynamic/interpreted code, unauthorized access, proxy behavior, and security vulnerabilities;
- deceptive behavior, misleading claims, dishonest behavior, and functionality that differs from promises;
- misrepresentation of identity, affiliation, content, or purpose;
- current target API policy.

### Malware and unwanted software

- malware, spyware, stalkerware, elevated privilege abuse, phishing, credential theft, ad fraud, hostile downloaders, and mobile unwanted software;
- runtime-loaded code and script behavior;
- bundled SDK behavior and supply-chain risk;
- transparent behavior, uninstallability, and clear disclosure.

### Monetization and ads

- Play payment requirements for digital goods/services and current allowed exceptions/programs;
- subscription disclosure, cancellation, pricing, trial, renewal, and benefit accuracy;
- disruptive, deceptive, inappropriate, or system-interfering ads;
- rewarded-ad grant integrity and ads shown to children;
- Families Self-Certified Ads SDK requirements.

Use `android-play-billing-entitlements` for implementation details. Do not route ordinary physical-goods or processor payment architecture through Play Billing unless current policy requires it.

### Store listing and promotion

- truthful metadata, title, icon, screenshots, feature graphics, videos, descriptions, developer identity, category, and tags;
- intellectual property, trademark, copyright, impersonation, and attribution;
- ratings, reviews, installs, promotion, incentives, and review manipulation;
- spam, repetitive content, webview-only/affiliate experiences, minimum functionality, broken functionality, and deceptive packaging;
- localization accuracy across every published translation and custom store listing.

### Other programs and form factors

Review current program rules when using Play Instant, Play Games Services, TV, Wear, Automotive, watch faces, education/families programs, subscriptions, alternative billing, external offers, pre-registration, or other Play programs.

## User-generated and AI-generated content

For UGC:

- require acceptance of terms/user policy before contribution;
- define prohibited content and behavior;
- provide accessible in-app reporting and blocking where applicable;
- moderate continuously and proportionally to content type, reach, and risk;
- enforce against content and accounts; preserve appeal/evidence controls as appropriate;
- protect minors and respond to child-safety reports under applicable law and policy;
- prevent monetization from incentivizing prohibited content;
- test anonymous, blocked, removed, appealed, and repeat-abuse behavior.

For generative AI:

- prevent generation of prohibited/restricted content using layered safeguards appropriate to the model and use case;
- provide in-app reporting/flagging without forcing users to leave the app;
- use reports to improve moderation and filtering;
- address impersonation, deception, election/voting misinformation, sexual content, child safety, self-harm, fraud, and dangerous capability risks relevant to the app;
- label or disclose synthetic output where product, law, or policy requires it;
- do not claim model certainty, professional qualification, or safety guarantees the system cannot provide;
- test adversarial prompts, multimodal inputs, account age, regional restrictions, and moderation failure recovery.

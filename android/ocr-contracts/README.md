# OCR contracts

Small vendor-independent Android library. Source kind, content kind and layout
role are independent. Candidate rank is scoped to a request/hypothesis/language;
an absent score remains null. There is no dependency on `app` or a commercial SDK.

The math availability contract represents an unavailable backend in this beta.
It is not a recognition implementation or an accuracy claim. A future SDK module
must implement actual recognition and lifetime contracts before returning math
results through the app.

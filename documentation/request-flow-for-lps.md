# Assign line manager (Nærmeste leder) from LPS viewpoint
The Nav api will share the need to assign a line manager (nærmeste leder) for an employee with the organization through Dialogporten.
It can be viewed in the Alinn innboks, or fetched by other systems through Dialogporten api.
Dialogporten is a system delivered by Altinn. Check out their [documentation](https://docs.altinn.studio/nb/dialogporten/reference/openapi/) for more information.

When the dialog is read by api, it contains link to a resource in Nav api to get National Identifier (fødselsnummer) and name of the employee in need of a line manager.

![narmesteleder uml diagram](lps-request-diagram.png)

Documentation for the api is available [here](https://esyfo-narmesteleder.ekstern.dev.nav.no/swagger)

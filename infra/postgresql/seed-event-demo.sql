INSERT INTO public.events (
    title, description, venue, starts_at, price, capacity, status, created_at
)
SELECT
    'Concert de démonstration TicketFlow',
    'Événement fictif pour le catalogue public.',
    'Casablanca',
    CURRENT_TIMESTAMP + INTERVAL '30 days',
    150.00,
    100,
    'PUBLISHED',
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM public.events
    WHERE title = 'Concert de démonstration TicketFlow'
    );
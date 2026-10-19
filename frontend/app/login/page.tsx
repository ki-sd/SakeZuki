import LoginForm from "@/components/auth/LoginForm";

export default async function LoginPage({ searchParams }: {
    searchParams: Promise<{ returnTo?: string; error?: string }>;
}) {
    const params = await searchParams;
    return <LoginForm returnTo={params.returnTo ?? null} oauthError={params.error === "oauth"} />;
}

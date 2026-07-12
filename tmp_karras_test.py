import math
T=1000
beta_start=0.00085
beta_end=0.012
alphas=[]
prod=1.0
for t in range(T):
    frac=t/(T-1)
    beta=(math.sqrt(beta_start)+frac*(math.sqrt(beta_end)-math.sqrt(beta_start)))**2
    alpha=1.0-beta
    prod*=alpha
    alphas.append(prod)

sigma_min=math.sqrt((1-alphas[0])/alphas[0])
sigma_max=math.sqrt((1-alphas[-1])/alphas[-1])
print('sigma_min',sigma_min,'sigma_max',sigma_max)

steps=7
rho=7.0
sigmas=[]
for i in range(steps):
    t=i/(steps-1 if steps-1>0 else 1)
    v=pow(sigma_max,1/rho)+t*(pow(sigma_min,1/rho)-pow(sigma_max,1/rho))
    sigmas.append(pow(v,rho))
print('sigmas',sigmas)

def alpha_from_sigma(sigma):
    return 1.0/(1.0+sigma*sigma)

def find_timestep(alpha):
    best=0
    bestd=abs(alphas[0]-alpha)
    for idx,a in enumerate(alphas):
        d=abs(a-alpha)
        if d<bestd:
            best=idx
            bestd=d
    return best

print('timesteps', [find_timestep(alpha_from_sigma(s)) for s in sigmas])
